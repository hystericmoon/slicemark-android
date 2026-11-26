package com.example.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.model.ExportProgressState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Shared coordinator bridging EditorViewModel and ExportForegroundService.
 */
object ExportCoordinator {
    private val _state = MutableStateFlow(ExportProgressState())
    val state: StateFlow<ExportProgressState> = _state.asStateFlow()

    @Volatile
    var pendingRequest: ExportJobRequest? = null
        private set

    val cancelFlag = AtomicBoolean(false)

    fun enqueue(context: Context, request: ExportJobRequest) {
        cancelFlag.set(false)
        pendingRequest = request
        _state.value = ExportProgressState(
            isExporting = true,
            currentClipIndex = 0,
            totalClips = request.clips.size,
            overallProgress = 0.02f,
            statusMessage = "Starting Slicemark export…",
        )
        val intent = Intent(context, ExportForegroundService::class.java).apply {
            action = ExportForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun requestCancel(context: Context) {
        cancelFlag.set(true)
        val intent = Intent(context, ExportForegroundService::class.java).apply {
            action = ExportForegroundService.ACTION_CANCEL
        }
        runCatching { context.startService(intent) }
    }

    internal fun updateProgress(state: ExportProgressState) {
        _state.value = state
    }

    internal fun consumeRequest(): ExportJobRequest? {
        val req = pendingRequest
        pendingRequest = null
        return req
    }

    fun clearCompletionBanner() {
        _state.value = _state.value.copy(
            lastCompletedSummary = null,
            errorMessage = null,
            isCancelled = false,
        )
    }
}

/**
 * Android 15-compliant `mediaProcessing` Foreground Service running long batch exports
 * with real progress notification, Cancel action, and clean `onTimeout` / `stopSelf` lifecycle.
 */
class ExportForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "slicemark_exports"
        const val NOTIFICATION_ID = 4108
        const val ACTION_START = "com.example.action.EXPORT_START"
        const val ACTION_CANCEL = "com.example.action.EXPORT_CANCEL"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                ExportCoordinator.cancelFlag.set(true)
                activeJob?.cancel()
                ExportCoordinator.updateProgress(
                    ExportProgressState(
                        isExporting = false,
                        isCancelled = true,
                        statusMessage = "Export cancelled",
                    )
                )
                stopForegroundCleanly()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val request = ExportCoordinator.consumeRequest()
                if (request == null) {
                    stopForegroundCleanly()
                    return START_NOT_STICKY
                }
                startInForeground(buildNotification("Preparing export…", 0, ongoing = true))
                launchExport(request)
            }
        }
        return START_NOT_STICKY
    }

    private fun launchExport(request: ExportJobRequest) {
        activeJob?.cancel()
        activeJob = serviceScope.launch {
            try {
                val outcome = VideoExportEngine.runExportJob(
                    context = applicationContext,
                    request = request,
                    isCancelled = { ExportCoordinator.cancelFlag.get() },
                    onProgress = { idx, total, progress, status ->
                        val pct = (progress * 100f).roundToInt().coerceIn(0, 100)
                        ExportCoordinator.updateProgress(
                            ExportProgressState(
                                isExporting = true,
                                currentClipIndex = idx,
                                totalClips = total,
                                overallProgress = progress,
                                statusMessage = status,
                            )
                        )
                        updateNotification(status, pct, ongoing = true)
                    },
                )
                ExportCoordinator.updateProgress(
                    ExportProgressState(
                        isExporting = false,
                        currentClipIndex = outcome.clipCount,
                        totalClips = outcome.clipCount,
                        overallProgress = 1f,
                        statusMessage = "Export complete",
                        lastCompletedUris = outcome.savedUris,
                        lastCompletedSummary = outcome.summary,
                    )
                )
                postCompletionNotification(outcome.summary)
            } catch (_: InterruptedException) {
                ExportCoordinator.updateProgress(
                    ExportProgressState(
                        isExporting = false,
                        isCancelled = true,
                        statusMessage = "Export cancelled",
                    )
                )
            } catch (t: Throwable) {
                ExportCoordinator.updateProgress(
                    ExportProgressState(
                        isExporting = false,
                        errorMessage = t.message ?: "Export failed on this clip format.",
                    )
                )
            } finally {
                stopForegroundCleanly()
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ExportCoordinator.cancelFlag.set(true)
        activeJob?.cancel()
        ExportCoordinator.updateProgress(
            ExportProgressState(
                isExporting = false,
                errorMessage = "Export timed out by system background limit.",
            )
        )
        stopForegroundCleanly()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCleanly() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.export_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.export_channel_desc)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun updateNotification(text: String, progress: Int, ongoing: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text, progress, ongoing))
    }

    private fun postCompletionNotification(summary: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Slicemark Export Ready")
            .setContentText(summary)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID + 1, n)
    }

    private fun buildNotification(text: String, progress: Int, ongoing: Boolean): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, ExportForegroundService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = PendingIntent.getActivity(
            this,
            12,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Slicemark Exporting")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, progress == 0)
            .addAction(0, "Cancel", cancelIntent)
            .build()
    }
}
