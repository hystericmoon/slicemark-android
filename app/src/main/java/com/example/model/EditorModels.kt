package com.example.model

import android.graphics.Bitmap
import android.net.Uri
import java.util.UUID

enum class EditorToolTab(val label: String) {
    TRIM("Trim"),
    TUNE("Tune"),
    FRAME("Frame"),
    TEXT("Text"),
    AUDIO("Audio"),
    EXPORT("Export"),
    CLIPS("Clips"),
}

enum class TrimWorkflow(val label: String) {
    BATCH_SPLIT("Batch Split"),
    FIXED_TRIM("Fixed Trim"),
    MANUAL("Manual"),
}

enum class SplitMethod(val label: String) {
    INTERVAL("Interval"),
    N_PARTS("N Parts"),
    MAX_MB("Max MB"),
}

enum class RemainderPolicy(val label: String) {
    KEEP("Keep"),
    DROP_SHORT("Drop <4s"),
    MERGE_LAST("Merge"),
}

enum class ManualMode(val label: String) {
    KEEP_SEGMENTS("Keep Segments"),
    REMOVE_MIDDLE("Remove Middle"),
}

enum class AspectPreset(val label: String, val ratio: Float?) {
    ORIGINAL("Original", null),
    PORTRAIT_9_16("9:16", 9f / 16f),
    SQUARE_1_1("1:1", 1f),
    SOCIAL_4_5("4:5", 4f / 5f),
    LANDSCAPE_16_9("16:9", 16f / 9f),
}

enum class FitMode(val label: String) {
    CROP_FILL("Crop Fill"),
    BLUR_BACKDROP("Blur Backdrop"),
    SOLID_MATTE("Solid Matte"),
}

enum class OverlayCorner(val label: String) {
    TOP_LEFT("Top Left"),
    TOP_RIGHT("Top Right"),
    BOTTOM_LEFT("Bottom Left"),
    BOTTOM_RIGHT("Bottom Right"),
}

enum class AutoTuneGoal(val label: String, val hint: String) {
    BALANCED_AUTO("Balanced Auto", "Natural studio polish & clean dynamics"),
    CINEMATIC("Cinematic", "Filmic contrast curve & rich tone"),
    SOCIAL_PUNCH("Social Punch", "Crisp pop, vibrant color & punchy voice"),
    CLEAN_STUDIO("Clean Studio", "Neutral whites, low noise & crisp edges"),
    PODCAST_VOICE("Podcast / Voice", "Speech clarity EQ, level voice & silence trim"),
}

enum class AutoTuneEngineType(val label: String, val badge: String) {
    LOCAL("Local Engine", "100% Offline • \$0"),
    OPENROUTER_BYOK("OpenRouter BYOK", "Cloud • Own Key"),
    GEMINI_BYOK("Gemini BYOK", "Cloud • Own Key"),
}

enum class ExportTargetMode(val label: String) {
    SEPARATE_CLIPS("Separate Clips"),
    MERGE_HIGHLIGHT("Merge Highlight"),
}

enum class ExportFormat(val label: String, val extension: String, val mimeType: String, val isAudioOnly: Boolean = false) {
    MP4("MP4", "mp4", "video/mp4"),
    WEBM("WebM", "webm", "video/webm"),
    GIF("GIF", "gif", "image/gif"),
    MP3("AAC / M4A", "m4a", "audio/mp4", isAudioOnly = true),
    WAV("WAV", "wav", "audio/wav", isAudioOnly = true),
}

enum class ExportResolution(val label: String, val maxShortSide: Int?) {
    ORIGINAL("Original", null),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
}

enum class ExportQualityPreset(val label: String, val bitrateScale: Float, val summary: String) {
    LOSSLESS("Lossless Fast", 1.0f, "Stream-copy MP4 when untouched"),
    HIGH("High Studio", 1.0f, "Crisp mastering bitrate"),
    BALANCED("Balanced", 0.72f, "Great quality, smaller files"),
    COMPACT("Compact Share", 0.48f, "Fast upload & messaging"),
}

data class SourceMedia(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int = 0,
    val fileSizeBytes: Long = 0L,
    val bitrateBps: Long = 0L,
    val frameRate: Float = 30f,
) {
    val effectiveWidth: Int get() = if (rotationDegrees % 180 == 90) height else width
    val effectiveHeight: Int get() = if (rotationDegrees % 180 == 90) width else height
    val aspectRatio: Float get() = if (effectiveHeight > 0) effectiveWidth.toFloat() / effectiveHeight else 16f / 9f
}

data class ClipSegment(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val startMs: Long,
    val endMs: Long,
    val isSelected: Boolean = true,
    val chapterTitle: String? = null,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(100L)
}

data class TrimConfig(
    val workflow: TrimWorkflow = TrimWorkflow.BATCH_SPLIT,
    val splitMethod: SplitMethod = SplitMethod.INTERVAL,
    val intervalSec: Int = 15,
    val targetPartCount: Int = 4,
    val maxFileSizeMb: Int = 10,
    val snapToSilence: Boolean = false,
    val overlapPreRollSec: Float = 0f,
    val remainderPolicy: RemainderPolicy = RemainderPolicy.KEEP,
    val fixedLengthSec: Int = 15,
    val manualMode: ManualMode = ManualMode.KEEP_SEGMENTS,
    val silenceThreshold: Float = 0.16f, // 0..1 amplitude threshold
    val minSilenceMs: Long = 700L,
    val sceneSensitivity: Float = 0.32f,
)

data class CropRectNormalized(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val width: Float get() = (right - left).coerceIn(0.15f, 1f)
    val height: Float get() = (bottom - top).coerceIn(0.15f, 1f)
    val isDefault: Boolean get() = left <= 0.005f && top <= 0.005f && right >= 0.995f && bottom >= 0.995f
}

data class VisualConfig(
    val aspectPreset: AspectPreset = AspectPreset.ORIGINAL,
    val fitMode: FitMode = FitMode.CROP_FILL,
    val matteColorArgb: Int = 0xFF000000.toInt(),
    val rotationDegrees: Int = 0, // 0, 90, 180, 270
    val flipHorizontal: Boolean = false,
    val cropRect: CropRectNormalized = CropRectNormalized(),
    val brightness: Float = 0f,     // -0.5..0.5
    val contrast: Float = 1f,       // 0.5..1.6
    val saturation: Float = 1f,     // 0.0..1.8
    val sharpen: Float = 0f,        // 0.0..1.0
    val denoiseEnabled: Boolean = false,
    val videoFadeInSec: Float = 0f,
    val videoFadeOutSec: Float = 0f,
) {
    val hasVisualAdjustments: Boolean
        get() = aspectPreset != AspectPreset.ORIGINAL ||
            rotationDegrees != 0 ||
            flipHorizontal ||
            !cropRect.isDefault ||
            kotlin.math.abs(brightness) > 0.005f ||
            kotlin.math.abs(contrast - 1f) > 0.01f ||
            kotlin.math.abs(saturation - 1f) > 0.01f ||
            sharpen > 0.01f ||
            denoiseEnabled ||
            videoFadeInSec > 0.01f ||
            videoFadeOutSec > 0.01f
}

data class SubtitleCue(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class ChapterMark(
    val startMs: Long,
    val title: String,
)

data class OverlayConfig(
    val autoPartStamp: Boolean = false,
    val partPrefix: String = "Part",
    val customWatermark: String = "",
    val burnTimecode: Boolean = false,
    val logoUri: Uri? = null,
    val logoDisplayName: String? = null,
    val corner: OverlayCorner = OverlayCorner.TOP_RIGHT,
    val opacity: Float = 0.85f,
    val subtitleFileName: String? = null,
    val subtitles: List<SubtitleCue> = emptyList(),
    val burnInCaptions: Boolean = false,
) {
    val hasActiveOverlays: Boolean
        get() = autoPartStamp ||
            customWatermark.isNotBlank() ||
            burnTimecode ||
            logoUri != null ||
            (burnInCaptions && subtitles.isNotEmpty())
}

data class AudioMotionConfig(
    val muted: Boolean = false,
    val volumeGain: Float = 1.0f, // 0.0..2.0
    val audioFadeInSec: Float = 0f,
    val audioFadeOutSec: Float = 0f,
    val loudnessNormalize: Boolean = false,
    val voiceClarityEq: Boolean = false,
    val bgTrackUri: Uri? = null,
    val bgTrackName: String? = null,
    val bgTrackVolume: Float = 0.25f, // 0..1
    val playbackSpeed: Float = 1.0f,  // 0.5..2.0
    val loopActiveClip: Boolean = true,
    val reverseEnabled: Boolean = false,
    val boomerangEnabled: Boolean = false,
) {
    val hasAudioOrMotionEffects: Boolean
        get() = muted ||
            kotlin.math.abs(volumeGain - 1f) > 0.01f ||
            audioFadeInSec > 0.01f ||
            audioFadeOutSec > 0.01f ||
            loudnessNormalize ||
            voiceClarityEq ||
            bgTrackUri != null ||
            kotlin.math.abs(playbackSpeed - 1f) > 0.01f ||
            reverseEnabled ||
            boomerangEnabled
}

data class ExportConfig(
    val targetMode: ExportTargetMode = ExportTargetMode.SEPARATE_CLIPS,
    val format: ExportFormat = ExportFormat.MP4,
    val resolution: ExportResolution = ExportResolution.ORIGINAL,
    val qualityPreset: ExportQualityPreset = ExportQualityPreset.LOSSLESS,
    val highlightFadeDipSec: Float = 0.35f,
    val bundleAsZip: Boolean = false, // Confirmed Phase 1 default: Individual clips in Movies/Slicemark
    val includePosterJpgs: Boolean = false,
)

data class TelemetrySnapshot(
    val sampledFrames: Int = 0,
    val meanLuma: Float = 0.5f,          // 0..1
    val p05Luma: Float = 0.1f,           // 0..1
    val p95Luma: Float = 0.9f,           // 0..1
    val contrastSpread: Float = 0.8f,    // p95 - p05
    val meanSaturation: Float = 0.4f,    // 0..1
    val edgeSharpness: Float = 0.5f,     // 0..1
    val shadowNoise: Float = 0.15f,      // 0..1
    val audioRmsDb: Float = -18f,        // -60..0 dB
    val quietRatio: Float = 0.15f,       // 0..1 fraction below quiet threshold
)

data class SuggestedHighlight(
    val label: String,
    val startMs: Long,
    val endMs: Long,
)

data class AutoTuneResult(
    val engineUsed: AutoTuneEngineType,
    val modelUsed: String,
    val fellBackToLocal: Boolean = false,
    val fallbackReason: String? = null,
    val goal: AutoTuneGoal,
    val lookTitle: String,
    val summary: String,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val sharpen: Float,
    val denoiseEnabled: Boolean,
    val volumeGain: Float,
    val loudnessNormalize: Boolean,
    val voiceClarityEq: Boolean,
    val recommendedAspect: AspectPreset,
    val recommendedSplitSec: Int,
    val recommendSilenceCut: Boolean,
    val suggestedHighlights: List<SuggestedHighlight> = emptyList(),
    val telemetry: TelemetrySnapshot,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class AutoTuneUiState(
    val goal: AutoTuneGoal = AutoTuneGoal.BALANCED_AUTO,
    val customDirection: String = "",
    val selectedEngine: AutoTuneEngineType = AutoTuneEngineType.LOCAL,
    val openRouterModelPreset: String = "meta-llama/llama-3.3-70b-instruct:free",
    val openRouterCustomModel: String = "",
    val geminiModelPreset: String = "gemini-2.5-flash",
    val geminiCustomModel: String = "",
    val hasOpenRouterKey: Boolean = false,
    val hasGeminiKey: Boolean = false,
    val isRunning: Boolean = false,
    val errorMessage: String? = null,
    val lastResult: AutoTuneResult? = null,
)

enum class HistoryEntryType {
    AUTO_TUNE_LOOK,
    EXPORT_RECORD,
}

data class HistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val type: HistoryEntryType,
    val title: String,
    val subtitle: String,
    val sourceVideoName: String,
    val timestampMs: Long = System.currentTimeMillis(),
    // Look snapshot fields (so "Apply Look" restores onto any loaded video)
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val sharpen: Float = 0f,
    val denoiseEnabled: Boolean = false,
    val aspectPresetName: String = AspectPreset.ORIGINAL.name,
    val fitModeName: String = FitMode.CROP_FILL.name,
    val volumeGain: Float = 1f,
    val loudnessNormalize: Boolean = false,
    val voiceClarityEq: Boolean = false,
    val exportSummary: String? = null,
)

data class ExportProgressState(
    val isExporting: Boolean = false,
    val currentClipIndex: Int = 0,
    val totalClips: Int = 0,
    val overallProgress: Float = 0f,
    val statusMessage: String = "",
    val lastCompletedUris: List<Uri> = emptyList(),
    val lastCompletedSummary: String? = null,
    val errorMessage: String? = null,
    val isCancelled: Boolean = false,
)

data class EditorSnapshot(
    val clips: List<ClipSegment>,
    val activeClipId: String?,
    val trimConfig: TrimConfig,
    val visualConfig: VisualConfig,
    val overlayConfig: OverlayConfig,
    val audioMotionConfig: AudioMotionConfig,
)

data class EditorUiState(
    val darkTheme: Boolean = true,
    val sourceMedia: SourceMedia? = null,
    val isLoadingMedia: Boolean = false,
    val loadingStatus: String = "",
    val filmstripFrames: List<Bitmap> = emptyList(),
    val waveformPeaks: List<Float> = emptyList(),
    val hasAudioTrack: Boolean = true,
    val isAnalyzingMedia: Boolean = false,
    val detectedSilenceRanges: List<LongRange> = emptyList(),
    val detectedSceneTimestampsMs: List<Long> = emptyList(),
    val clips: List<ClipSegment> = emptyList(),
    val activeClipId: String? = null,
    val playheadMs: Long = 0L,
    val isPlaying: Boolean = false,
    val timelineZoom: Float = 1f, // 1.0x..6.0x
    val snapEnabled: Boolean = true,
    val compareOriginal: Boolean = false,
    val showCropReticle: Boolean = false,
    val activeToolTab: EditorToolTab = EditorToolTab.TRIM,
    val portraitSheetOpen: Boolean = false,
    val trimConfig: TrimConfig = TrimConfig(),
    val visualConfig: VisualConfig = VisualConfig(),
    val overlayConfig: OverlayConfig = OverlayConfig(),
    val audioMotionConfig: AudioMotionConfig = AudioMotionConfig(),
    val exportConfig: ExportConfig = ExportConfig(),
    val autoTune: AutoTuneUiState = AutoTuneUiState(),
    val exportProgress: ExportProgressState = ExportProgressState(),
    val history: List<HistoryEntry> = emptyList(),
    val isAnalyzingSmartCut: Boolean = false,
    val smartCutMessage: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val toastMessage: String? = null,
) {
    val activeClip: ClipSegment?
        get() = clips.firstOrNull { it.id == activeClipId } ?: clips.firstOrNull()

    val activeClipIndex: Int
        get() = clips.indexOfFirst { it.id == activeClip?.id }.coerceAtLeast(0)

    val selectedClips: List<ClipSegment>
        get() = clips.filter { it.isSelected }

    val selectedTotalDurationMs: Long
        get() = selectedClips.sumOf { it.durationMs }

    val isLosslessEligible: Boolean
        get() = exportConfig.format == ExportFormat.MP4 &&
            exportConfig.targetMode == ExportTargetMode.SEPARATE_CLIPS &&
            exportConfig.resolution == ExportResolution.ORIGINAL &&
            exportConfig.qualityPreset == ExportQualityPreset.LOSSLESS &&
            !visualConfig.hasVisualAdjustments &&
            !overlayConfig.hasActiveOverlays &&
            !audioMotionConfig.hasAudioOrMotionEffects
}

fun formatTimecode(ms: Long, includeCentiseconds: Boolean = true): String {
    val safeMs = ms.coerceAtLeast(0L)
    val totalSeconds = safeMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    val cs = (safeMs % 1000L) / 10L
    return if (hours > 0) {
        if (includeCentiseconds) String.format("%d:%02d:%02d.%02d", hours, minutes, seconds, cs)
        else String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        if (includeCentiseconds) String.format("%02d:%02d.%02d", minutes, seconds, cs)
        else String.format("%02d:%02d", minutes, seconds)
    }
}

fun formatCompactDuration(ms: Long): String {
    val totalSec = (ms / 1000.0)
    return when {
        totalSec >= 60.0 -> {
            val m = (totalSec / 60).toInt()
            val s = (totalSec % 60).toInt()
            "${m}m ${s}s"
        }
        else -> String.format("%.1fs", totalSec)
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1024.0 -> String.format("%.2f GB", mb / 1024.0)
        mb >= 1.0 -> String.format("%.1f MB", mb)
        else -> String.format("%.0f KB", kb)
    }
}
