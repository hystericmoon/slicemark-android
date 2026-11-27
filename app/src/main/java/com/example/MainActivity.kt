package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.ClipSegment
import com.example.model.SubtitleAndChapterParser
import com.example.ui.components.ChaptersTimestampDialog
import com.example.ui.components.LocalHistorySheet
import com.example.ui.screens.EditorWorkspaceScreen
import com.example.ui.screens.LaunchScreen
import com.example.ui.theme.SlicemarkTheme
import com.example.viewmodel.EditorViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val vm: EditorViewModel = viewModel()
            val state by vm.uiState.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }

            var showHistorySheet by remember { mutableStateOf(false) }
            var showChaptersModal by remember { mutableStateOf(false) }
            var pendingExportClips by remember { mutableStateOf<List<ClipSegment>?>(null) }

            // Zero-permission Photo Picker for videos
            val videoPhotoPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.PickVisualMedia(),
                onResult = { uri ->
                    if (uri != null) vm.loadVideoUri(uri)
                },
            )

            // SAF OpenDocument fallback for video files
            val videoSafPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
                onResult = { uri ->
                    if (uri != null) {
                        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        vm.loadVideoUri(uri)
                    }
                },
            )

            // SAF OpenDocument for PNG/SVG Logo
            val logoPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
                onResult = { uri ->
                    if (uri != null) {
                        val name = queryFileName(uri) ?: "logo"
                        vm.updateOverlay(recordUndo = true) { it.copy(logoUri = uri, logoDisplayName = name) }
                    }
                },
            )

            // SAF OpenDocument for .SRT / .VTT subtitles
            val subtitlePicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
                onResult = { uri ->
                    if (uri != null) {
                        val name = queryFileName(uri) ?: "captions.srt"
                        vm.importSubtitlesFromUri(uri, name)
                    }
                },
            )

            // SAF OpenDocument for Background Audio / Voiceover
            val audioPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
                onResult = { uri ->
                    if (uri != null) {
                        val name = queryFileName(uri) ?: "audio_track"
                        vm.updateAudioMotion(recordUndo = true) { it.copy(bgTrackUri = uri, bgTrackName = name) }
                    }
                },
            )

            // Runtime POST_NOTIFICATIONS launcher for Android 13+ foreground export notification
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = {
                    // Proceed with export whether granted or denied (FGS still runs safely)
                    vm.startExport(pendingExportClips)
                    pendingExportClips = null
                },
            )

            fun triggerExport(clipsOverride: List<ClipSegment>?) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        pendingExportClips = clipsOverride
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        return
                    }
                }
                vm.startExport(clipsOverride)
            }

            fun shareExportedUris(uris: List<Uri>) {
                if (uris.isEmpty()) return
                val shareIntent = if (uris.size == 1) {
                    Intent(Intent.ACTION_SEND).apply {
                        type = contentResolver.getType(uris.first()) ?: "*/*"
                        putExtra(Intent.EXTRA_STREAM, uris.first())
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                startActivity(Intent.createChooser(shareIntent, "Share via Slicemark"))
            }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) {
                        vm.pausePlayback()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            LaunchedEffect(state.toastMessage) {
                val msg = state.toastMessage
                if (!msg.isNullOrBlank()) {
                    snackbarHostState.showSnackbar(msg)
                    vm.clearToast()
                }
            }

            SlicemarkTheme(darkTheme = state.darkTheme) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (state.sourceMedia == null) {
                        LaunchScreen(
                            state = state,
                            onSelectVideoPhotoPicker = {
                                videoPhotoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                )
                            },
                            onSelectVideoSaf = { videoSafPicker.launch(arrayOf("video/*")) },
                            onOpenHistory = { showHistorySheet = true },
                            onToggleTheme = { vm.toggleDarkTheme() },
                        )
                    } else {
                        BackHandler(enabled = state.portraitSheetOpen || showHistorySheet || showChaptersModal) {
                            when {
                                showHistorySheet -> showHistorySheet = false
                                showChaptersModal -> showChaptersModal = false
                                state.portraitSheetOpen -> vm.closePortraitSheet()
                            }
                        }

                        EditorWorkspaceScreen(
                            state = state,
                            player = vm.player,
                            viewModel = vm,
                            onReplaceVideo = {
                                videoPhotoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                )
                            },
                            onOpenHistory = { showHistorySheet = true },
                            onOpenChaptersModal = { showChaptersModal = true },
                            onPickLogo = { logoPicker.launch(arrayOf("image/png", "image/svg+xml", "image/*")) },
                            onPickSubtitleFile = {
                                subtitlePicker.launch(arrayOf("application/x-subrip", "text/vtt", "text/plain", "*/*"))
                            },
                            onPickBgAudio = { audioPicker.launch(arrayOf("audio/*")) },
                            onTriggerExport = { clips -> triggerExport(clips) },
                            onShareExportUris = { uris -> shareExportedUris(uris) },
                        )
                    }

                    if (showHistorySheet) {
                        LocalHistorySheet(
                            history = state.history,
                            hasLoadedVideo = state.sourceMedia != null,
                            onApplyLook = { entry -> vm.applyHistoryLook(entry) },
                            onDeleteItem = { id -> vm.deleteHistoryEntry(id) },
                            onClearAll = { vm.clearHistory() },
                            onDismiss = { showHistorySheet = false },
                        )
                    }

                    if (showChaptersModal) {
                        ChaptersTimestampDialog(
                            initialText = SubtitleAndChapterParser.exportChaptersText(state.clips),
                            onApplyChapters = { raw -> vm.applyChaptersText(raw) },
                            onDismiss = { showChaptersModal = false },
                        )
                    }

                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 72.dp),
                    )
                }
            }
        }
    }

    private fun queryFileName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()
    }
}
