package com.example.viewmodel

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.autotune.InvalidApiKeyException
import com.example.autotune.SmartAutoTuneEngine
import com.example.data.LocalPreferencesRepository
import com.example.data.SecureKeyStore
import com.example.media.AudioAnalysisResult
import com.example.media.ExportCoordinator
import com.example.media.ExportJobRequest
import com.example.media.MediaAnalyzer
import com.example.media.MediaStoreHelper
import com.example.media.SampleVideoGenerator
import com.example.model.AspectPreset
import com.example.model.AudioMotionConfig
import com.example.model.AutoTuneEngineType
import com.example.model.AutoTuneGoal
import com.example.model.AutoTuneResult
import com.example.model.ClipSegment
import com.example.model.CropRectNormalized
import com.example.model.EditorSnapshot
import com.example.model.EditorToolTab
import com.example.model.EditorUiState
import com.example.model.ExportConfig
import com.example.model.ExportFormat
import com.example.model.ExportQualityPreset
import com.example.model.ExportResolution
import com.example.model.ExportTargetMode
import com.example.model.FitMode
import com.example.model.HistoryEntry
import com.example.model.HistoryEntryType
import com.example.model.ManualMode
import com.example.model.OverlayConfig
import com.example.model.OverlayCorner
import com.example.model.RemainderPolicy
import com.example.model.SourceMedia
import com.example.model.SplitMethod
import com.example.model.SubtitleAndChapterParser
import com.example.model.TelemetrySnapshot
import com.example.model.TrimConfig
import com.example.model.TrimWorkflow
import com.example.model.VisualConfig
import com.example.model.formatCompactDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsRepo = LocalPreferencesRepository(application)
    private val keyStore = SecureKeyStore(application)

    private val _uiState = MutableStateFlow(
        EditorUiState(
            autoTune = com.example.model.AutoTuneUiState(
                hasOpenRouterKey = keyStore.hasOpenRouterKey(),
                hasGeminiKey = keyStore.hasGeminiKey(),
            )
        )
    )
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    val player: ExoPlayer = ExoPlayer.Builder(application).build()

    private val undoStack = ArrayDeque<EditorSnapshot>()
    private val redoStack = ArrayDeque<EditorSnapshot>()
    private var lastAudioAnalysis: AudioAnalysisResult = AudioAnalysisResult(emptyList(), -20f, 0.2f)
    private var cachedTelemetry: TelemetrySnapshot? = null
    private var analysisJob: Job? = null
    private var playheadPollJob: Job? = null
    private var lastRecordedExportSummary: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        viewModelScope.launch {
            prefsRepo.darkThemeFlow.collect { dark ->
                _uiState.update { it.copy(darkTheme = dark) }
            }
        }
        viewModelScope.launch {
            prefsRepo.historyFlow.collect { history ->
                _uiState.update { it.copy(history = history) }
            }
        }
        viewModelScope.launch {
            ExportCoordinator.state.collect { exportState ->
                _uiState.update { it.copy(exportProgress = exportState) }
                val summary = exportState.lastCompletedSummary
                if (!exportState.isExporting && summary != null && summary != lastRecordedExportSummary) {
                    lastRecordedExportSummary = summary
                    val srcName = _uiState.value.sourceMedia?.displayName ?: "Video"
                    recordExportInHistory(summary, srcName)
                }
            }
        }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying) startPlayheadPolling() else stopPlayheadPolling()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val state = _uiState.value
                    val active = state.activeClip
                    if (active != null && state.audioMotionConfig.loopActiveClip) {
                        seekTo(active.startMs)
                        player.play()
                    } else {
                        _uiState.update { it.copy(isPlaying = false) }
                    }
                }
            }
        })
    }

    override fun onCleared() {
        analysisJob?.cancel()
        playheadPollJob?.cancel()
        player.release()
        super.onCleared()
    }

    private fun startPlayheadPolling() {
        playheadPollJob?.cancel()
        playheadPollJob = viewModelScope.launch {
            while (isActive) {
                val pos = player.currentPosition.coerceAtLeast(0L)
                val state = _uiState.value
                val active = state.activeClip
                if (active != null && state.audioMotionConfig.loopActiveClip && pos >= active.endMs) {
                    player.seekTo(active.startMs)
                    _uiState.update { it.copy(playheadMs = active.startMs) }
                } else {
                    _uiState.update { it.copy(playheadMs = pos) }
                }
                delay(40L)
            }
        }
    }

    private fun stopPlayheadPolling() {
        playheadPollJob?.cancel()
        _uiState.update { it.copy(playheadMs = player.currentPosition.coerceAtLeast(0L)) }
    }

    // --- Theme & Navigation ---

    fun toggleDarkTheme() {
        val next = !_uiState.value.darkTheme
        _uiState.update { it.copy(darkTheme = next) }
        viewModelScope.launch { prefsRepo.setDarkTheme(next) }
    }

    fun selectToolTab(tab: EditorToolTab, openSheet: Boolean = true) {
        _uiState.update {
            it.copy(
                activeToolTab = tab,
                portraitSheetOpen = openSheet,
                showCropReticle = tab == EditorToolTab.FRAME && it.showCropReticle,
            )
        }
    }

    fun closePortraitSheet() {
        _uiState.update { it.copy(portraitSheetOpen = false) }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun showToast(msg: String) {
        _uiState.update { it.copy(toastMessage = msg) }
    }

    // --- Media Loading ---

    fun loadSampleClip() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMedia = true, loadingStatus = "Synthesizing HD studio sample clip…") }
            val result = runCatching { SampleVideoGenerator.getOrCreateSampleVideo(getApplication()) }
            result.onSuccess { uri ->
                loadVideoInternal(uri, isSample = true)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoadingMedia = false,
                        loadingStatus = "",
                        toastMessage = "Failed to generate sample clip: ${err.message}",
                    )
                }
            }
        }
    }

    fun loadVideoUri(uri: Uri) {
        viewModelScope.launch {
            loadVideoInternal(uri, isSample = false)
        }
    }

    fun closeProjectToHome() {
        analysisJob?.cancel()
        player.pause()
        player.clearMediaItems()
        undoStack.clear()
        redoStack.clear()
        cachedTelemetry = null
        _uiState.update {
            it.copy(
                sourceMedia = null,
                filmstripFrames = emptyList(),
                waveformPeaks = emptyList(),
                detectedSilenceRanges = emptyList(),
                detectedSceneTimestampsMs = emptyList(),
                clips = emptyList(),
                activeClipId = null,
                playheadMs = 0L,
                isPlaying = false,
                portraitSheetOpen = false,
                canUndo = false,
                canRedo = false,
                smartCutMessage = null,
            )
        }
    }

    private suspend fun loadVideoInternal(uri: Uri, isSample: Boolean) {
        analysisJob?.cancel()
        undoStack.clear()
        redoStack.clear()
        cachedTelemetry = null
        _uiState.update {
            it.copy(
                isLoadingMedia = true,
                loadingStatus = "Inspecting video stream…",
                filmstripFrames = emptyList(),
                waveformPeaks = emptyList(),
                detectedSilenceRanges = emptyList(),
                detectedSceneTimestampsMs = emptyList(),
                smartCutMessage = null,
                canUndo = false,
                canRedo = false,
            )
        }

        val sourceResult = runCatching { MediaAnalyzer.inspectSourceMedia(getApplication(), uri, isSample) }
        val source = sourceResult.getOrElse { err ->
            _uiState.update {
                it.copy(
                    isLoadingMedia = false,
                    loadingStatus = "",
                    toastMessage = "Unable to open video: ${err.message}",
                )
            }
            return
        }

        // Initialize default trim & clip queue
        val defaultTrim = TrimConfig(
            workflow = TrimWorkflow.BATCH_SPLIT,
            splitMethod = SplitMethod.INTERVAL,
            intervalSec = if (source.durationMs <= 20_000L) 6 else 15,
        )
        val initialClips = computeBatchClips(source, defaultTrim, emptyList())

        mainHandler.post {
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.seekTo(initialClips.firstOrNull()?.startMs ?: 0L)
            syncPlayerParameters(_uiState.value.audioMotionConfig)
        }

        _uiState.update {
            it.copy(
                sourceMedia = source,
                isLoadingMedia = false,
                loadingStatus = "",
                trimConfig = defaultTrim,
                clips = initialClips,
                activeClipId = initialClips.firstOrNull()?.id,
                playheadMs = initialClips.firstOrNull()?.startMs ?: 0L,
                visualConfig = VisualConfig(),
                overlayConfig = OverlayConfig(),
                audioMotionConfig = AudioMotionConfig(),
                compareOriginal = false,
                showCropReticle = false,
                autoTune = it.autoTune.copy(lastResult = null, errorMessage = null),
            )
        }

        // Background extraction: filmstrip thumbnails + PCM waveform
        analysisJob = viewModelScope.launch {
            val frames = MediaAnalyzer.extractFilmstrip(getApplication(), uri, source.durationMs, count = 14)
            _uiState.update { it.copy(filmstripFrames = frames) }

            val audio = MediaAnalyzer.extractAudioWaveform(getApplication(), uri, source.durationMs, barsCount = 96)
            lastAudioAnalysis = audio
            val silences = MediaAnalyzer.detectSilenceRanges(
                waveformPeaks = audio.waveformPeaks,
                durationMs = source.durationMs,
                threshold = _uiState.value.trimConfig.silenceThreshold,
                minSilenceMs = _uiState.value.trimConfig.minSilenceMs,
            )
            _uiState.update {
                it.copy(
                    waveformPeaks = audio.waveformPeaks,
                    detectedSilenceRanges = silences,
                )
            }
        }
    }

    // --- Playback & Transport ---

    fun togglePlayPause() {
        val state = _uiState.value
        if (state.sourceMedia == null) return
        if (player.isPlaying) {
            player.pause()
        } else {
            val active = state.activeClip
            if (active != null && (player.currentPosition < active.startMs || player.currentPosition >= active.endMs - 80L)) {
                player.seekTo(active.startMs)
            }
            player.play()
        }
    }

    fun seekTo(ms: Long) {
        val dur = _uiState.value.sourceMedia?.durationMs ?: return
        val clamped = ms.coerceIn(0L, dur)
        player.seekTo(clamped)
        _uiState.update { it.copy(playheadMs = clamped) }
    }

    fun scrubTimelineTo(rawMs: Long) {
        val state = _uiState.value
        val dur = state.sourceMedia?.durationMs ?: return
        val snapped = if (state.snapEnabled) snapTimestamp(rawMs, state) else rawMs
        seekTo(snapped.coerceIn(0L, dur))
    }

    fun stepFrame(direction: Int) {
        val state = _uiState.value
        val fps = state.sourceMedia?.frameRate?.coerceIn(12f, 120f) ?: 30f
        val frameMs = (1000f / fps).roundToInt().toLong().coerceAtLeast(16L)
        if (player.isPlaying) player.pause()
        seekTo(state.playheadMs + direction * frameMs)
    }

    fun jumpToClipStart() {
        _uiState.value.activeClip?.let { seekTo(it.startMs) }
    }

    fun jumpToClipEnd() {
        _uiState.value.activeClip?.let { seekTo(it.endMs) }
    }

    fun setTimelineZoom(zoom: Float) {
        _uiState.update { it.copy(timelineZoom = zoom.coerceIn(1f, 6f)) }
    }

    fun toggleSnap() {
        _uiState.update { it.copy(snapEnabled = !it.snapEnabled) }
    }

    fun toggleCompareOriginal() {
        _uiState.update { it.copy(compareOriginal = !it.compareOriginal) }
    }

    fun toggleCropReticle() {
        _uiState.update { it.copy(showCropReticle = !it.showCropReticle) }
    }

    // --- Undo / Redo ---

    private fun pushUndoSnapshot() {
        val s = _uiState.value
        if (s.sourceMedia == null) return
        undoStack.addLast(
            EditorSnapshot(
                clips = s.clips,
                activeClipId = s.activeClipId,
                trimConfig = s.trimConfig,
                visualConfig = s.visualConfig,
                overlayConfig = s.overlayConfig,
                audioMotionConfig = s.audioMotionConfig,
            )
        )
        while (undoStack.size > 40) undoStack.removeFirst()
        redoStack.clear()
        _uiState.update { it.copy(canUndo = undoStack.isNotEmpty(), canRedo = false) }
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        val s = _uiState.value
        redoStack.addLast(
            EditorSnapshot(
                clips = s.clips,
                activeClipId = s.activeClipId,
                trimConfig = s.trimConfig,
                visualConfig = s.visualConfig,
                overlayConfig = s.overlayConfig,
                audioMotionConfig = s.audioMotionConfig,
            )
        )
        _uiState.update {
            it.copy(
                clips = prev.clips,
                activeClipId = prev.activeClipId,
                trimConfig = prev.trimConfig,
                visualConfig = prev.visualConfig,
                overlayConfig = prev.overlayConfig,
                audioMotionConfig = prev.audioMotionConfig,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
            )
        }
        syncPlayerParameters(prev.audioMotionConfig)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        val s = _uiState.value
        undoStack.addLast(
            EditorSnapshot(
                clips = s.clips,
                activeClipId = s.activeClipId,
                trimConfig = s.trimConfig,
                visualConfig = s.visualConfig,
                overlayConfig = s.overlayConfig,
                audioMotionConfig = s.audioMotionConfig,
            )
        )
        _uiState.update {
            it.copy(
                clips = next.clips,
                activeClipId = next.activeClipId,
                trimConfig = next.trimConfig,
                visualConfig = next.visualConfig,
                overlayConfig = next.overlayConfig,
                audioMotionConfig = next.audioMotionConfig,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
            )
        }
        syncPlayerParameters(next.audioMotionConfig)
    }

    // --- Clip Editing & Trim Workflows ---

    fun markInAtPlayhead() {
        val state = _uiState.value
        val active = state.activeClip ?: return
        val target = state.playheadMs.coerceIn(0L, active.endMs - 300L)
        pushUndoSnapshot()
        updateActiveClipRange(target, active.endMs)
    }

    fun markOutAtPlayhead() {
        val state = _uiState.value
        val active = state.activeClip ?: return
        val dur = state.sourceMedia?.durationMs ?: return
        val target = state.playheadMs.coerceIn(active.startMs + 300L, dur)
        pushUndoSnapshot()
        updateActiveClipRange(active.startMs, target)
    }

    fun updateActiveClipRange(startMs: Long, endMs: Long, recordUndo: Boolean = false) {
        val state = _uiState.value
        val dur = state.sourceMedia?.durationMs ?: return
        val active = state.activeClip ?: return
        if (recordUndo) pushUndoSnapshot()

        val safeStart = if (state.snapEnabled) snapTimestamp(startMs, state) else startMs
        val safeEnd = if (state.snapEnabled) snapTimestamp(endMs, state) else endMs
        val clampedStart = safeStart.coerceIn(0L, dur - 300L)
        val clampedEnd = safeEnd.coerceIn(clampedStart + 300L, dur)

        val updated = state.clips.map {
            if (it.id == active.id) it.copy(startMs = clampedStart, endMs = clampedEnd) else it
        }
        _uiState.update { it.copy(clips = updated) }
    }

    fun splitAtPlayhead() {
        val state = _uiState.value
        val active = state.activeClip ?: return
        val pos = state.playheadMs
        if (pos <= active.startMs + 250L || pos >= active.endMs - 250L) {
            showToast("Move playhead inside the active clip to split.")
            return
        }
        pushUndoSnapshot()
        val idx = state.clips.indexOfFirst { it.id == active.id }
        val first = active.copy(endMs = pos)
        val second = ClipSegment(
            id = UUID.randomUUID().toString(),
            label = "${active.label.ifBlank { "Clip" }} B",
            startMs = pos,
            endMs = active.endMs,
            isSelected = active.isSelected,
        )
        val newList = state.clips.toMutableList().apply {
            set(idx, first)
            add(idx + 1, second)
        }
        _uiState.update {
            it.copy(
                clips = relabelClips(newList),
                activeClipId = second.id,
                toastMessage = "Split clip at ${com.example.model.formatTimecode(pos)}",
            )
        }
    }

    fun selectClip(clipId: String, seekAndPreview: Boolean = true) {
        val clip = _uiState.value.clips.firstOrNull { it.id == clipId } ?: return
        _uiState.update { it.copy(activeClipId = clipId) }
        if (seekAndPreview) {
            seekTo(clip.startMs)
            player.play()
        }
    }

    fun toggleClipSelection(clipId: String) {
        pushUndoSnapshot()
        _uiState.update { state ->
            state.copy(
                clips = state.clips.map { if (it.id == clipId) it.copy(isSelected = !it.isSelected) else it }
            )
        }
    }

    fun selectAllClips(selected: Boolean) {
        pushUndoSnapshot()
        _uiState.update { state ->
            state.copy(clips = state.clips.map { it.copy(isSelected = selected) })
        }
    }

    fun moveClip(clipId: String, direction: Int) {
        val list = _uiState.value.clips.toMutableList()
        val idx = list.indexOfFirst { it.id == clipId }
        val target = idx + direction
        if (idx < 0 || target !in list.indices) return
        pushUndoSnapshot()
        val item = list.removeAt(idx)
        list.add(target, item)
        _uiState.update { it.copy(clips = list) }
    }

    fun deleteClip(clipId: String) {
        val list = _uiState.value.clips
        if (list.size <= 1) {
            showToast("At least one clip must remain in the queue.")
            return
        }
        pushUndoSnapshot()
        val next = list.filterNot { it.id == clipId }
        val nextActive = if (_uiState.value.activeClipId == clipId) next.firstOrNull()?.id else _uiState.value.activeClipId
        _uiState.update { it.copy(clips = next, activeClipId = nextActive) }
    }

    fun updateTrimConfig(recalculateClips: Boolean = true, transform: (TrimConfig) -> TrimConfig) {
        val state = _uiState.value
        val source = state.sourceMedia ?: return
        val nextTrim = transform(state.trimConfig)
        pushUndoSnapshot()
        val nextClips = if (recalculateClips) {
            when (nextTrim.workflow) {
                TrimWorkflow.BATCH_SPLIT -> computeBatchClips(source, nextTrim, state.detectedSilenceRanges)
                TrimWorkflow.FIXED_TRIM -> {
                    val windowMs = (nextTrim.fixedLengthSec * 1000L).coerceIn(1000L, source.durationMs)
                    val curStart = (state.activeClip?.startMs ?: 0L).coerceIn(0L, (source.durationMs - windowMs).coerceAtLeast(0L))
                    listOf(ClipSegment(label = "Fixed ${nextTrim.fixedLengthSec}s", startMs = curStart, endMs = curStart + windowMs))
                }
                TrimWorkflow.MANUAL -> {
                    if (nextTrim.manualMode == ManualMode.REMOVE_MIDDLE && state.clips.size == 1) {
                        val c = state.clips.first()
                        val mid = (c.startMs + c.endMs) / 2L
                        val gap = min(1500L, c.durationMs / 4L)
                        listOf(
                            ClipSegment(label = "Intro Keep", startMs = c.startMs, endMs = (mid - gap).coerceAtLeast(c.startMs + 400L)),
                            ClipSegment(label = "Outro Keep", startMs = (mid + gap).coerceAtMost(c.endMs - 400L), endMs = c.endMs),
                        )
                    } else state.clips
                }
            }
        } else state.clips

        _uiState.update {
            it.copy(
                trimConfig = nextTrim,
                clips = nextClips,
                activeClipId = nextClips.firstOrNull()?.id,
            )
        }
    }

    fun slideFixedTrimWindow(newStartMs: Long) {
        val state = _uiState.value
        val source = state.sourceMedia ?: return
        val windowMs = (state.trimConfig.fixedLengthSec * 1000L).coerceIn(1000L, source.durationMs)
        val start = newStartMs.coerceIn(0L, (source.durationMs - windowMs).coerceAtLeast(0L))
        val active = state.activeClip ?: return
        _uiState.update { s ->
            s.copy(
                clips = s.clips.map { if (it.id == active.id) it.copy(startMs = start, endMs = start + windowMs) else it }
            )
        }
        seekTo(start)
    }

    fun applyRemoveMiddleJumpCut() {
        val state = _uiState.value
        val source = state.sourceMedia ?: return
        val active = state.activeClip ?: return
        val pos = state.playheadMs
        val cutHalf = 1000L
        if (pos <= active.startMs + 600L || pos >= active.endMs - 600L) {
            showToast("Place playhead inside the clip to punch out a 2s middle jump-cut.")
            return
        }
        pushUndoSnapshot()
        val leftEnd = (pos - cutHalf).coerceAtLeast(active.startMs + 300L)
        val rightStart = (pos + cutHalf).coerceAtMost(min(active.endMs - 300L, source.durationMs))
        val idx = state.clips.indexOfFirst { it.id == active.id }
        val newList = state.clips.toMutableList().apply {
            set(idx, active.copy(endMs = leftEnd))
            add(idx + 1, ClipSegment(label = "Jump Cut B", startMs = rightStart, endMs = active.endMs))
        }
        _uiState.update {
            it.copy(
                clips = relabelClips(newList),
                toastMessage = "Removed middle section for clean jump-cut stitch.",
            )
        }
    }

    fun runSilenceJumpCut() {
        val state = _uiState.value
        val source = state.sourceMedia ?: return
        if (state.waveformPeaks.isEmpty()) {
            showToast("Audio waveform is still analyzing…")
            return
        }
        pushUndoSnapshot()
        val silences = MediaAnalyzer.detectSilenceRanges(
            waveformPeaks = state.waveformPeaks,
            durationMs = source.durationMs,
            threshold = state.trimConfig.silenceThreshold,
            minSilenceMs = state.trimConfig.minSilenceMs,
        )
        if (silences.isEmpty()) {
            _uiState.update {
                it.copy(
                    detectedSilenceRanges = emptyList(),
                    smartCutMessage = "No silences longer than ${state.trimConfig.minSilenceMs}ms at ${(state.trimConfig.silenceThreshold * 100).roundToInt()}% threshold.",
                )
            }
            return
        }

        // Build active speech segments between silence gaps
        val kept = mutableListOf<ClipSegment>()
        var cursor = 0L
        val pad = 120L
        for (gap in silences) {
            val end = (gap.first + pad).coerceAtMost(source.durationMs)
            if (end - cursor >= 500L) {
                kept.add(ClipSegment(startMs = cursor, endMs = end))
            }
            cursor = (gap.last - pad).coerceAtLeast(end)
        }
        if (source.durationMs - cursor >= 500L) {
            kept.add(ClipSegment(startMs = cursor, endMs = source.durationMs))
        }

        val finalClips = if (kept.isNotEmpty()) relabelClips(kept) else state.clips
        _uiState.update {
            it.copy(
                detectedSilenceRanges = silences,
                clips = finalClips,
                activeClipId = finalClips.firstOrNull()?.id,
                smartCutMessage = "Removed ${silences.size} quiet pauses → ${finalClips.size} active segments.",
            )
        }
    }

    fun runSceneChangeDetection() {
        val state = _uiState.value
        val source = state.sourceMedia ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzingSmartCut = true, smartCutMessage = "Scanning visual scene transitions…") }
            val cuts = MediaAnalyzer.detectSceneChanges(
                context = getApplication(),
                uri = source.uri,
                durationMs = source.durationMs,
                sensitivity = state.trimConfig.sceneSensitivity,
            )
            if (cuts.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isAnalyzingSmartCut = false,
                        detectedSceneTimestampsMs = emptyList(),
                        smartCutMessage = "No sharp scene changes detected.",
                    )
                }
                return@launch
            }
            pushUndoSnapshot()
            val boundaries = listOf(0L) + cuts + listOf(source.durationMs)
            val segments = boundaries.zipWithNext().mapIndexedNotNull { idx, (s, e) ->
                if (e - s >= 500L) ClipSegment(label = "Scene ${idx + 1}", startMs = s, endMs = e) else null
            }
            _uiState.update {
                it.copy(
                    isAnalyzingSmartCut = false,
                    detectedSceneTimestampsMs = cuts,
                    clips = segments,
                    activeClipId = segments.firstOrNull()?.id,
                    smartCutMessage = "Detected ${cuts.size} scene transitions → ${segments.size} clips.",
                )
            }
        }
    }

    fun applyChaptersText(rawText: String) {
        val source = _uiState.value.sourceMedia ?: return
        val marks = SubtitleAndChapterParser.parseChapters(rawText, source.durationMs)
        if (marks.isEmpty()) {
            showToast("No valid timestamps found (e.g., 00:00 Intro).")
            return
        }
        pushUndoSnapshot()
        val segments = marks.mapIndexed { idx, mark ->
            val nextStart = if (idx + 1 < marks.size) marks[idx + 1].startMs else source.durationMs
            ClipSegment(
                label = mark.title,
                startMs = mark.startMs,
                endMs = nextStart.coerceAtLeast(mark.startMs + 500L),
                chapterTitle = mark.title,
            )
        }
        _uiState.update {
            it.copy(
                clips = segments,
                activeClipId = segments.firstOrNull()?.id,
                smartCutMessage = "Imported ${segments.size} YouTube chapters into Clip Queue.",
            )
        }
    }

    private fun computeBatchClips(
        source: SourceMedia,
        trim: TrimConfig,
        silences: List<LongRange>,
    ): List<ClipSegment> {
        val dur = source.durationMs.coerceAtLeast(500L)
        val intervalMs = when (trim.splitMethod) {
            SplitMethod.INTERVAL -> (trim.intervalSec.coerceIn(3, 600) * 1000L)
            SplitMethod.N_PARTS -> (dur / trim.targetPartCount.coerceIn(2, 50)).coerceAtLeast(1000L)
            SplitMethod.MAX_MB -> {
                val targetBytes = trim.maxFileSizeMb.coerceIn(2, 500) * 1024L * 1024L
                val bytesPerSec = if (source.fileSizeBytes > 0L) {
                    (source.fileSizeBytes.toDouble() / (dur / 1000.0)).coerceAtLeast(64_000.0)
                } else {
                    (source.bitrateBps / 8.0).coerceAtLeast(128_000.0)
                }
                (((targetBytes * 0.90) / bytesPerSec) * 1000.0).toLong().coerceIn(3000L, dur)
            }
        }

        val overlapMs = (trim.overlapPreRollSec * 1000f).toLong().coerceAtLeast(0L)
        val rawSegments = mutableListOf<Pair<Long, Long>>()
        var cursor = 0L

        while (cursor < dur) {
            var end = (cursor + intervalMs).coerceAtMost(dur)
            if (trim.snapToSilence && end < dur && silences.isNotEmpty()) {
                val nearest = silences.minByOrNull { range ->
                    val mid = (range.first + range.last) / 2L
                    abs(mid - end)
                }
                if (nearest != null) {
                    val mid = (nearest.first + nearest.last) / 2L
                    if (abs(mid - end) <= 3000L && mid > cursor + 1000L) {
                        end = mid
                    }
                }
            }
            val startWithPreRoll = if (rawSegments.isEmpty()) cursor else (cursor - overlapMs).coerceAtLeast(0L)
            rawSegments.add(startWithPreRoll to end)
            cursor = end
        }

        // Apply trailing remainder policy
        if (rawSegments.size > 1) {
            val last = rawSegments.last()
            val lastDur = last.second - last.first
            if (lastDur < 4000L) {
                when (trim.remainderPolicy) {
                    RemainderPolicy.KEEP -> Unit
                    RemainderPolicy.DROP_SHORT -> rawSegments.removeAt(rawSegments.lastIndex)
                    RemainderPolicy.MERGE_LAST -> {
                        val prev = rawSegments[rawSegments.lastIndex - 1]
                        rawSegments[rawSegments.lastIndex - 1] = prev.first to last.second
                        rawSegments.removeAt(rawSegments.lastIndex)
                    }
                }
            }
        }

        val prefix = source.displayName.substringBeforeLast('.').take(18).ifBlank { "Clip" }
        return rawSegments.mapIndexed { idx, (s, e) ->
            val num = String.format("%02d", idx + 1)
            val sec = ((e - s) / 1000L).coerceAtLeast(1L)
            ClipSegment(
                label = "${prefix}_${num}_${sec}s",
                startMs = s,
                endMs = e,
                isSelected = true,
            )
        }
    }

    private fun relabelClips(list: List<ClipSegment>): List<ClipSegment> {
        val prefix = _uiState.value.sourceMedia?.displayName?.substringBeforeLast('.')?.take(18).orEmpty().ifBlank { "Clip" }
        return list.mapIndexed { idx, c ->
            val num = String.format("%02d", idx + 1)
            val sec = (c.durationMs / 1000L).coerceAtLeast(1L)
            c.copy(label = c.chapterTitle ?: "${prefix}_${num}_${sec}s")
        }
    }

    private fun snapTimestamp(rawMs: Long, state: EditorUiState): Long {
        val candidates = buildList {
            state.detectedSilenceRanges.forEach { add((it.first + it.last) / 2L) }
            state.detectedSceneTimestampsMs.forEach { add(it) }
            state.clips.forEach {
                add(it.startMs)
                add(it.endMs)
            }
        }
        val thresholdMs = (450f / state.timelineZoom).toLong().coerceAtLeast(120L)
        val best = candidates.minByOrNull { abs(it - rawMs) } ?: return rawMs
        return if (abs(best - rawMs) <= thresholdMs) best else rawMs
    }

    // --- Visual, Framing, Overlays & Subtitles ---

    fun updateVisual(recordUndo: Boolean = false, transform: (VisualConfig) -> VisualConfig) {
        if (recordUndo) pushUndoSnapshot()
        _uiState.update { it.copy(visualConfig = transform(it.visualConfig), compareOriginal = false) }
    }

    fun rotate90() {
        pushUndoSnapshot()
        _uiState.update {
            val nextRot = (it.visualConfig.rotationDegrees + 90) % 360
            it.copy(visualConfig = it.visualConfig.copy(rotationDegrees = nextRot), compareOriginal = false)
        }
    }

    fun toggleFlipHorizontal() {
        pushUndoSnapshot()
        _uiState.update {
            it.copy(
                visualConfig = it.visualConfig.copy(flipHorizontal = !it.visualConfig.flipHorizontal),
                compareOriginal = false,
            )
        }
    }

    fun resetCrop() {
        pushUndoSnapshot()
        _uiState.update {
            it.copy(visualConfig = it.visualConfig.copy(cropRect = CropRectNormalized()))
        }
    }

    fun updateOverlay(recordUndo: Boolean = false, transform: (OverlayConfig) -> OverlayConfig) {
        if (recordUndo) pushUndoSnapshot()
        _uiState.update { it.copy(overlayConfig = transform(it.overlayConfig)) }
    }

    fun importSubtitlesFromUri(uri: Uri, displayName: String) {
        viewModelScope.launch {
            val text = runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()
            val cues = SubtitleAndChapterParser.parseSubtitles(text)
            if (cues.isEmpty()) {
                showToast("No valid .SRT or .VTT cues found in file.")
                return@launch
            }
            pushUndoSnapshot()
            _uiState.update {
                it.copy(
                    overlayConfig = it.overlayConfig.copy(
                        subtitleFileName = displayName,
                        subtitles = cues,
                        burnInCaptions = true,
                    ),
                    toastMessage = "Loaded ${cues.size} subtitle cues from $displayName",
                )
            }
        }
    }

    fun clearSubtitles() {
        pushUndoSnapshot()
        _uiState.update {
            it.copy(
                overlayConfig = it.overlayConfig.copy(
                    subtitleFileName = null,
                    subtitles = emptyList(),
                    burnInCaptions = false,
                )
            )
        }
    }

    // --- Audio & Motion ---

    fun updateAudioMotion(recordUndo: Boolean = false, transform: (AudioMotionConfig) -> AudioMotionConfig) {
        if (recordUndo) pushUndoSnapshot()
        val next = transform(_uiState.value.audioMotionConfig)
        _uiState.update { it.copy(audioMotionConfig = next) }
        syncPlayerParameters(next)
    }

    private fun syncPlayerParameters(audio: AudioMotionConfig) {
        mainHandler.post {
            player.volume = if (audio.muted) 0f else audio.volumeGain.coerceIn(0f, 1f)
            player.playbackParameters = PlaybackParameters(audio.playbackSpeed.coerceIn(0.5f, 2.0f))
        }
    }

    // --- Smart Auto-Tune (3-Engine Architecture) ---

    fun setAutoTuneGoal(goal: AutoTuneGoal) {
        _uiState.update { it.copy(autoTune = it.autoTune.copy(goal = goal, errorMessage = null)) }
    }

    fun setAutoTuneDirection(text: String) {
        _uiState.update { it.copy(autoTune = it.autoTune.copy(customDirection = text)) }
    }

    fun setAutoTuneEngine(engine: AutoTuneEngineType) {
        _uiState.update { it.copy(autoTune = it.autoTune.copy(selectedEngine = engine, errorMessage = null)) }
    }

    fun setOpenRouterModelPreset(model: String) {
        _uiState.update { it.copy(autoTune = it.autoTune.copy(openRouterModelPreset = model)) }
    }

    fun setOpenRouterCustomModel(model: String) {
        _uiState.update { it.copy(autoTune = it.autoTune.copy(openRouterCustomModel = model)) }
    }

    fun setGeminiModelPreset(model: String) {
        _uiState.update { it.copy(autoTune = it.autoTune.copy(geminiModelPreset = model)) }
    }

    fun setGeminiCustomModel(model: String) {
        _uiState.update { it.copy(autoTune = it.autoTune.copy(geminiCustomModel = model)) }
    }

    fun saveByokKey(engine: AutoTuneEngineType, key: String) {
        when (engine) {
            AutoTuneEngineType.OPENROUTER_BYOK -> keyStore.saveOpenRouterKey(key)
            AutoTuneEngineType.GEMINI_BYOK -> keyStore.saveGeminiKey(key)
            AutoTuneEngineType.LOCAL -> Unit
        }
        _uiState.update {
            it.copy(
                autoTune = it.autoTune.copy(
                    hasOpenRouterKey = keyStore.hasOpenRouterKey(),
                    hasGeminiKey = keyStore.hasGeminiKey(),
                    errorMessage = null,
                ),
                toastMessage = if (key.isBlank()) "Cleared API key" else "API key encrypted on-device",
            )
        }
    }

    fun runSmartAutoTune() {
        val state = _uiState.value
        val source = state.sourceMedia ?: return
        if (state.autoTune.isRunning) return

        viewModelScope.launch {
            _uiState.update { it.copy(autoTune = it.autoTune.copy(isRunning = true, errorMessage = null)) }
            try {
                val telemetry = cachedTelemetry ?: MediaAnalyzer.computeKeyframeTelemetry(
                    context = getApplication(),
                    uri = source.uri,
                    durationMs = source.durationMs,
                    audioAnalysis = lastAudioAnalysis,
                ).also { cachedTelemetry = it }

                val engine = state.autoTune.selectedEngine
                val apiKey = when (engine) {
                    AutoTuneEngineType.LOCAL -> ""
                    AutoTuneEngineType.OPENROUTER_BYOK -> keyStore.getOpenRouterKey()
                    AutoTuneEngineType.GEMINI_BYOK -> keyStore.getGeminiKey()
                }
                val model = when (engine) {
                    AutoTuneEngineType.LOCAL -> "local"
                    AutoTuneEngineType.OPENROUTER_BYOK ->
                        state.autoTune.openRouterCustomModel.ifBlank { state.autoTune.openRouterModelPreset }
                    AutoTuneEngineType.GEMINI_BYOK ->
                        state.autoTune.geminiCustomModel.ifBlank { state.autoTune.geminiModelPreset }
                }

                val result = SmartAutoTuneEngine.runAutoTune(
                    engine = engine,
                    goal = state.autoTune.goal,
                    customDirection = state.autoTune.customDirection,
                    telemetry = telemetry,
                    source = source,
                    waveformPeaks = state.waveformPeaks,
                    apiKey = apiKey,
                    selectedModel = model,
                )

                pushUndoSnapshot()
                val nextAudio = state.audioMotionConfig.copy(
                    volumeGain = result.volumeGain,
                    loudnessNormalize = result.loudnessNormalize,
                    voiceClarityEq = result.voiceClarityEq,
                )
                _uiState.update { s ->
                    s.copy(
                        visualConfig = s.visualConfig.copy(
                            brightness = result.brightness,
                            contrast = result.contrast,
                            saturation = result.saturation,
                            sharpen = result.sharpen,
                            denoiseEnabled = result.denoiseEnabled,
                        ),
                        audioMotionConfig = nextAudio,
                        compareOriginal = false,
                        autoTune = s.autoTune.copy(
                            isRunning = false,
                            errorMessage = null,
                            lastResult = result,
                        ),
                        toastMessage = if (result.fellBackToLocal) {
                            "Cloud busy — applied Local offline Auto-Tune"
                        } else {
                            "Applied ${result.lookTitle}"
                        },
                    )
                }
                syncPlayerParameters(nextAudio)
                recordAutoTuneInHistory(result, source.displayName)
            } catch (e: InvalidApiKeyException) {
                _uiState.update {
                    it.copy(
                        autoTune = it.autoTune.copy(
                            isRunning = false,
                            errorMessage = e.message,
                        )
                    )
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        autoTune = it.autoTune.copy(
                            isRunning = false,
                            errorMessage = t.message ?: "Auto-Tune failed unexpectedly.",
                        )
                    )
                }
            }
        }
    }

    fun applyAutoTuneRecommendedAspect(aspect: AspectPreset) {
        updateVisual(recordUndo = true) { it.copy(aspectPreset = aspect) }
        showToast("Framed to ${aspect.label}")
    }

    fun applyAutoTuneRecommendedSplit(seconds: Int) {
        updateTrimConfig(recalculateClips = true) {
            it.copy(
                workflow = TrimWorkflow.BATCH_SPLIT,
                splitMethod = SplitMethod.INTERVAL,
                intervalSec = seconds,
            )
        }
        showToast("Split queue into ${seconds}s parts")
    }

    fun loadAutoTuneHighlights(result: AutoTuneResult) {
        if (result.suggestedHighlights.isEmpty()) return
        pushUndoSnapshot()
        val clips = result.suggestedHighlights.mapIndexed { idx, h ->
            ClipSegment(
                label = "${h.label} (${formatCompactDuration(h.endMs - h.startMs)})",
                startMs = h.startMs,
                endMs = h.endMs,
                isSelected = true,
            )
        }
        _uiState.update {
            it.copy(
                clips = clips,
                activeClipId = clips.firstOrNull()?.id,
                toastMessage = "Loaded ${clips.size} AI highlight clips into Queue",
            )
        }
    }

    // --- Local History (Max 20 on-device entries) ---

    private fun recordAutoTuneInHistory(result: AutoTuneResult, sourceName: String) {
        val entry = HistoryEntry(
            type = HistoryEntryType.AUTO_TUNE_LOOK,
            title = result.lookTitle,
            subtitle = "${result.engineUsed.label} • ${result.summary}",
            sourceVideoName = sourceName,
            brightness = result.brightness,
            contrast = result.contrast,
            saturation = result.saturation,
            sharpen = result.sharpen,
            denoiseEnabled = result.denoiseEnabled,
            aspectPresetName = result.recommendedAspect.name,
            fitModeName = _uiState.value.visualConfig.fitMode.name,
            volumeGain = result.volumeGain,
            loudnessNormalize = result.loudnessNormalize,
            voiceClarityEq = result.voiceClarityEq,
        )
        viewModelScope.launch { prefsRepo.addHistoryEntry(entry) }
    }

    private fun recordExportInHistory(summary: String, sourceName: String) {
        val s = _uiState.value
        val entry = HistoryEntry(
            type = HistoryEntryType.EXPORT_RECORD,
            title = "Exported ${s.exportConfig.format.label} (${s.selectedClips.size.coerceAtLeast(1)} clips)",
            subtitle = summary,
            sourceVideoName = sourceName,
            brightness = s.visualConfig.brightness,
            contrast = s.visualConfig.contrast,
            saturation = s.visualConfig.saturation,
            sharpen = s.visualConfig.sharpen,
            denoiseEnabled = s.visualConfig.denoiseEnabled,
            aspectPresetName = s.visualConfig.aspectPreset.name,
            fitModeName = s.visualConfig.fitMode.name,
            volumeGain = s.audioMotionConfig.volumeGain,
            loudnessNormalize = s.audioMotionConfig.loudnessNormalize,
            voiceClarityEq = s.audioMotionConfig.voiceClarityEq,
            exportSummary = summary,
        )
        viewModelScope.launch { prefsRepo.addHistoryEntry(entry) }
    }

    fun applyHistoryLook(entry: HistoryEntry) {
        if (_uiState.value.sourceMedia == null) {
            showToast("Load a video first to apply \"${entry.title}\".")
            return
        }
        pushUndoSnapshot()
        val aspect = runCatching { AspectPreset.valueOf(entry.aspectPresetName) }.getOrDefault(AspectPreset.ORIGINAL)
        val fit = runCatching { FitMode.valueOf(entry.fitModeName) }.getOrDefault(FitMode.CROP_FILL)
        val nextAudio = _uiState.value.audioMotionConfig.copy(
            volumeGain = entry.volumeGain,
            loudnessNormalize = entry.loudnessNormalize,
            voiceClarityEq = entry.voiceClarityEq,
        )
        _uiState.update {
            it.copy(
                visualConfig = it.visualConfig.copy(
                    brightness = entry.brightness,
                    contrast = entry.contrast,
                    saturation = entry.saturation,
                    sharpen = entry.sharpen,
                    denoiseEnabled = entry.denoiseEnabled,
                    aspectPreset = aspect,
                    fitMode = fit,
                ),
                audioMotionConfig = nextAudio,
                compareOriginal = false,
                toastMessage = "Applied look \"${entry.title}\"",
            )
        }
        syncPlayerParameters(nextAudio)
    }

    fun deleteHistoryEntry(id: String) {
        viewModelScope.launch { prefsRepo.deleteHistoryEntry(id) }
    }

    fun clearHistory() {
        viewModelScope.launch { prefsRepo.clearHistory() }
    }

    // --- Snapshot & Export ---

    fun saveFrameSnapshot() {
        val state = _uiState.value
        val source = state.sourceMedia ?: return
        viewModelScope.launch {
            val uri = MediaStoreHelper.saveFrameSnapshotPng(
                context = getApplication(),
                source = source,
                positionMs = state.playheadMs,
                visual = state.visualConfig,
                overlay = state.overlayConfig,
                clipIndex = state.activeClipIndex,
                totalClips = state.clips.size,
                compareOriginal = state.compareOriginal,
            )
            showToast(
                if (uri != null) "Saved PNG frame snapshot to Pictures/Slicemark"
                else "Unable to capture frame snapshot at this timestamp."
            )
        }
    }

    fun updateExportConfig(transform: (ExportConfig) -> ExportConfig) {
        _uiState.update { it.copy(exportConfig = transform(it.exportConfig)) }
    }

    fun startExport(clipsOverride: List<ClipSegment>? = null) {
        val state = _uiState.value
        val source = state.sourceMedia ?: return
        val targets = (clipsOverride ?: state.selectedClips).ifEmpty {
            listOfNotNull(state.activeClip)
        }
        if (targets.isEmpty()) {
            showToast("Select at least one clip to export.")
            return
        }
        val req = ExportJobRequest(
            source = source,
            clips = targets,
            visual = state.visualConfig,
            overlay = state.overlayConfig,
            audioMotion = state.audioMotionConfig,
            export = state.exportConfig,
        )
        ExportCoordinator.enqueue(getApplication(), req)
    }

    fun cancelExport() {
        ExportCoordinator.requestCancel(getApplication())
    }

    fun dismissExportOutcome() {
        ExportCoordinator.clearCompletionBanner()
    }
}
