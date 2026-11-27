package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.model.AudioMotionConfig
import com.example.model.ClipSegment
import com.example.model.ExportConfig
import com.example.model.ExportFormat
import com.example.model.ExportQualityPreset
import com.example.model.ExportResolution
import com.example.model.ExportTargetMode
import com.example.model.FitMode
import com.example.model.OverlayConfig
import com.example.model.SourceMedia
import com.example.model.SubtitleAndChapterParser
import com.example.model.VisualConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ExportJobRequest(
    val source: SourceMedia,
    val clips: List<ClipSegment>,
    val visual: VisualConfig,
    val overlay: OverlayConfig,
    val audioMotion: AudioMotionConfig,
    val export: ExportConfig,
)

data class ExportJobOutcome(
    val savedUris: List<Uri>,
    val summary: String,
    val clipCount: Int,
)

object VideoExportEngine {

    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun runExportJob(
        context: Context,
        request: ExportJobRequest,
        isCancelled: () -> Boolean,
        onProgress: (clipIndex: Int, totalClips: Int, progress: Float, status: String) -> Unit,
    ): ExportJobOutcome = withContext(Dispatchers.IO) {
        val workDir = File(context.cacheDir, "export_work_${System.currentTimeMillis()}").apply { mkdirs() }
        val baseName = request.source.displayName.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "slicemark" }
        val clips = request.clips.ifEmpty {
            listOf(ClipSegment(label = "Clip 1", startMs = 0L, endMs = request.source.durationMs))
        }
        val logoBitmap = MediaStoreHelper.loadLogoBitmap(context, request.overlay.logoUri)

        val producedFiles = mutableListOf<Triple<File, String, String>>() // file, displayName, mimeType
        val zipExtraFiles = mutableListOf<Pair<File, String>>() // SRTs & poster JPGs

        try {
            val isMerge = request.export.targetMode == ExportTargetMode.MERGE_HIGHLIGHT && clips.size > 1
            val totalSteps = if (isMerge) 1 else clips.size

            if (isMerge) {
                if (isCancelled()) throw InterruptedException("Export cancelled")
                onProgress(0, 1, 0.05f, "Merging ${clips.size} clips into highlight reel…")
                val outExt = resolveOutputExtension(request.export.format)
                val outMime = resolveOutputMime(request.export.format)
                val outName = "${baseName}_highlight.$outExt"
                val outFile = File(workDir, outName)

                exportHighlightReel(
                    context = context,
                    request = request,
                    clips = clips,
                    logoBitmap = logoBitmap,
                    outputFile = outFile,
                    isCancelled = isCancelled,
                    onStepProgress = { p -> onProgress(0, 1, p, "Rendering highlight reel ${(p * 100).roundToInt()}%") },
                )
                producedFiles.add(Triple(outFile, outName, outMime))

                if (request.export.includePosterJpgs) {
                    createPosterJpg(context, request, clips.first(), 0, clips.size, logoBitmap, File(workDir, "${baseName}_highlight_cover.jpg"))?.let {
                        zipExtraFiles.add(it to it.name)
                    }
                }
            } else {
                for ((idx, clip) in clips.withIndex()) {
                    if (isCancelled()) throw InterruptedException("Export cancelled")
                    val partNum = String.format("%02d", idx + 1)
                    val secTag = "${(clip.durationMs / 1000L).coerceAtLeast(1)}s"
                    val outExt = resolveOutputExtension(request.export.format)
                    val outMime = resolveOutputMime(request.export.format)
                    val outName = "${baseName}_${partNum}_${secTag}.$outExt"
                    val outFile = File(workDir, outName)

                    onProgress(
                        idx,
                        totalSteps,
                        idx.toFloat() / totalSteps,
                        "Exporting clip ${idx + 1}/$totalSteps (${request.export.format.label})…",
                    )

                    exportSingleClip(
                        context = context,
                        request = request,
                        clip = clip,
                        clipIndex = idx,
                        totalClips = clips.size,
                        logoBitmap = logoBitmap,
                        outputFile = outFile,
                        isCancelled = isCancelled,
                        onClipProgress = { p ->
                            val overall = (idx + p) / totalSteps
                            onProgress(idx, totalSteps, overall, "Clip ${idx + 1}/$totalSteps • ${(p * 100).roundToInt()}%")
                        },
                    )
                    producedFiles.add(Triple(outFile, outName, outMime))

                    // Auto-slice re-timed SRT per clip when subtitles are loaded
                    if (request.overlay.subtitles.isNotEmpty()) {
                        val srtText = SubtitleAndChapterParser.sliceAndRetimeSrtForClip(
                            cues = request.overlay.subtitles,
                            clipStartMs = clip.startMs,
                            clipEndMs = clip.endMs,
                            speed = request.audioMotion.playbackSpeed,
                        )
                        if (srtText.isNotBlank()) {
                            val srtFile = File(workDir, "${baseName}_${partNum}_${secTag}.srt")
                            srtFile.writeText(srtText)
                            zipExtraFiles.add(srtFile to srtFile.name)
                        }
                    }

                    // Optional poster cover JPG per clip
                    if (request.export.includePosterJpgs) {
                        val jpgFile = File(workDir, "${baseName}_${partNum}_poster.jpg")
                        createPosterJpg(context, request, clip, idx, clips.size, logoBitmap, jpgFile)?.let {
                            zipExtraFiles.add(it to it.name)
                        }
                    }
                }
            }

            if (isCancelled()) throw InterruptedException("Export cancelled")

            val savedUris = mutableListOf<Uri>()
            val summary: String

            val shouldZip = request.export.bundleAsZip && (producedFiles.size > 1 || zipExtraFiles.isNotEmpty())
            if (shouldZip) {
                onProgress(totalSteps, totalSteps, 0.94f, "Packaging ZIP archive…")
                val zipName = "${baseName}_slicemark_${clips.size}clips.zip"
                val zipFile = File(workDir, zipName)
                ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                    for ((file, name, _) in producedFiles) {
                        if (file.exists()) writeZipEntry(zos, file, name)
                    }
                    for ((file, name) in zipExtraFiles) {
                        if (file.exists()) writeZipEntry(zos, file, name)
                    }
                }
                val zipUri = MediaStoreHelper.saveFileToMediaStore(
                    context = context,
                    sourceFile = zipFile,
                    displayName = zipName,
                    mimeType = "application/zip",
                )
                savedUris.add(zipUri)
                val extrasNote = if (zipExtraFiles.isNotEmpty()) " + ${zipExtraFiles.size} SRT/poster files" else ""
                summary = "Saved ZIP ($zipName, ${producedFiles.size} clips$extrasNote) to Downloads/Slicemark"
            } else {
                onProgress(totalSteps, totalSteps, 0.94f, "Saving to device library…")
                for ((file, name, mime) in producedFiles) {
                    if (file.exists()) {
                        savedUris.add(
                            MediaStoreHelper.saveFileToMediaStore(
                                context = context,
                                sourceFile = file,
                                displayName = name,
                                mimeType = mime,
                            )
                        )
                    }
                }
                if (request.export.includePosterJpgs) {
                    for ((file, name) in zipExtraFiles.filter { it.second.endsWith(".jpg") }) {
                        savedUris.add(
                            MediaStoreHelper.saveFileToMediaStore(
                                context = context,
                                sourceFile = file,
                                displayName = name,
                                mimeType = "image/jpeg",
                            )
                        )
                    }
                }
                val folderLabel = when {
                    request.export.format.isAudioOnly -> "Music/Slicemark"
                    request.export.format == ExportFormat.GIF -> "Downloads/Slicemark"
                    else -> "Movies/Slicemark"
                }
                summary = if (producedFiles.size == 1) {
                    "Saved ${producedFiles.first().second} to $folderLabel"
                } else {
                    "Saved ${producedFiles.size} ${request.export.format.label} files to $folderLabel"
                }
            }

            onProgress(totalSteps, totalSteps, 1f, "Complete")
            ExportJobOutcome(savedUris = savedUris, summary = summary, clipCount = producedFiles.size)
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    private suspend fun exportSingleClip(
        context: Context,
        request: ExportJobRequest,
        clip: ClipSegment,
        clipIndex: Int,
        totalClips: Int,
        logoBitmap: Bitmap?,
        outputFile: File,
        isCancelled: () -> Boolean,
        onClipProgress: (Float) -> Unit,
    ) {
        val fmt = request.export.format
        val isUntouchedMp4 = fmt == ExportFormat.MP4 &&
            request.export.resolution == ExportResolution.ORIGINAL &&
            request.export.qualityPreset == ExportQualityPreset.LOSSLESS &&
            !request.visual.hasVisualAdjustments &&
            !request.overlay.hasActiveOverlays &&
            !request.audioMotion.hasAudioOrMotionEffects

        when {
            fmt == ExportFormat.WAV -> {
                onClipProgress(0.3f)
                AudioTrackExporter.exportClipsAudioToWav(
                    context = context,
                    videoUri = request.source.uri,
                    clips = listOf(clip),
                    config = request.audioMotion,
                    outputWavFile = outputFile,
                )
                onClipProgress(1f)
            }
            fmt == ExportFormat.MP3 -> {
                onClipProgress(0.25f)
                val needsOfflineDsp = request.audioMotion.reverseEnabled ||
                    request.audioMotion.boomerangEnabled ||
                    request.audioMotion.bgTrackUri != null
                val succeeded = if (!needsOfflineDsp) {
                    runCatching {
                        runMedia3Transformer(
                            context = context,
                            request = request,
                            clips = listOf(clip),
                            clipIndex = clipIndex,
                            totalClips = totalClips,
                            logoBitmap = logoBitmap,
                            outputFile = outputFile,
                            audioOnly = true,
                            useWebm = false,
                            isCancelled = isCancelled,
                            onProgress = onClipProgress,
                        )
                    }.isSuccess
                } else false
                if (!succeeded || !outputFile.exists() || outputFile.length() < 128L) {
                    AudioTrackExporter.exportClipsAudioToM4a(
                        context = context,
                        videoUri = request.source.uri,
                        clips = listOf(clip),
                        config = request.audioMotion,
                        outputM4aFile = outputFile,
                    )
                }
                onClipProgress(1f)
            }
            fmt == ExportFormat.GIF -> {
                exportAnimatedGif(
                    context = context,
                    request = request,
                    clips = listOf(clip),
                    clipIndex = clipIndex,
                    totalClips = totalClips,
                    logoBitmap = logoBitmap,
                    outputFile = outputFile,
                    isCancelled = isCancelled,
                    onProgress = onClipProgress,
                )
            }
            isUntouchedMp4 -> {
                val ok = runCatching {
                    losslessStreamCopyMp4(context, request.source.uri, clip.startMs, clip.endMs, outputFile, onClipProgress)
                }.getOrDefault(false)
                if (!ok) {
                    runMedia3Transformer(
                        context = context,
                        request = request,
                        clips = listOf(clip),
                        clipIndex = clipIndex,
                        totalClips = totalClips,
                        logoBitmap = logoBitmap,
                        outputFile = outputFile,
                        audioOnly = false,
                        useWebm = false,
                        isCancelled = isCancelled,
                        onProgress = onClipProgress,
                    )
                }
            }
            request.audioMotion.reverseEnabled ||
                request.audioMotion.boomerangEnabled ||
                request.visual.fitMode != FitMode.CROP_FILL ||
                request.visual.videoFadeInSec > 0.01f ||
                request.visual.videoFadeOutSec > 0.01f ||
                request.visual.sharpen > 0.02f ||
                request.visual.denoiseEnabled ||
                (request.overlay.burnInCaptions && request.overlay.subtitles.isNotEmpty()) -> {
                // Full styled frame-buffer renderer supports Reverse, Boomerang, Blur Backdrop, Solid Matte, Sharpen/Denoise, Video Fades, Burn-In Captions & synchronized DSP AAC audio
                exportStyledFrameBufferedVideo(
                    context = context,
                    request = request,
                    clips = listOf(clip),
                    clipIndex = clipIndex,
                    totalClips = totalClips,
                    logoBitmap = logoBitmap,
                    outputFile = outputFile,
                    isCancelled = isCancelled,
                    onProgress = onClipProgress,
                )
            }
            else -> {
                val useWebm = fmt == ExportFormat.WEBM
                val transformed = runCatching {
                    runMedia3Transformer(
                        context = context,
                        request = request,
                        clips = listOf(clip),
                        clipIndex = clipIndex,
                        totalClips = totalClips,
                        logoBitmap = logoBitmap,
                        outputFile = outputFile,
                        audioOnly = false,
                        useWebm = useWebm,
                        isCancelled = isCancelled,
                        onProgress = onClipProgress,
                    )
                }.isSuccess
                if (!transformed || !outputFile.exists() || outputFile.length() < 1024L) {
                    exportStyledFrameBufferedVideo(
                        context = context,
                        request = request,
                        clips = listOf(clip),
                        clipIndex = clipIndex,
                        totalClips = totalClips,
                        logoBitmap = logoBitmap,
                        outputFile = outputFile,
                        isCancelled = isCancelled,
                        onProgress = onClipProgress,
                    )
                }
            }
        }
    }

    private suspend fun exportHighlightReel(
        context: Context,
        request: ExportJobRequest,
        clips: List<ClipSegment>,
        logoBitmap: Bitmap?,
        outputFile: File,
        isCancelled: () -> Boolean,
        onStepProgress: (Float) -> Unit,
    ) {
        when (request.export.format) {
            ExportFormat.GIF -> exportAnimatedGif(
                context, request, clips, 0, clips.size, logoBitmap, outputFile, isCancelled, onStepProgress
            )
            ExportFormat.WAV -> {
                AudioTrackExporter.exportClipsAudioToWav(
                    context, request.source.uri, clips, request.audioMotion, outputFile
                )
                onStepProgress(1f)
            }
            ExportFormat.MP3 -> {
                AudioTrackExporter.exportClipsAudioToM4a(
                    context, request.source.uri, clips, request.audioMotion, outputFile
                )
                onStepProgress(1f)
            }
            else -> {
                val useCanvasRenderer = request.export.highlightFadeDipSec > 0.02f ||
                    request.audioMotion.reverseEnabled ||
                    request.audioMotion.boomerangEnabled ||
                    request.audioMotion.bgTrackUri != null ||
                    request.visual.fitMode != FitMode.CROP_FILL ||
                    request.visual.sharpen > 0.01f ||
                    request.visual.denoiseEnabled ||
                    (request.overlay.burnInCaptions && request.overlay.subtitles.isNotEmpty())
                if (useCanvasRenderer) {
                    exportStyledFrameBufferedVideo(
                        context, request, clips, 0, clips.size, logoBitmap, outputFile, isCancelled, onStepProgress
                    )
                } else {
                    val ok = runCatching {
                        runMedia3Transformer(
                            context = context,
                            request = request,
                            clips = clips,
                            clipIndex = 0,
                            totalClips = clips.size,
                            logoBitmap = logoBitmap,
                            outputFile = outputFile,
                            audioOnly = false,
                            useWebm = request.export.format == ExportFormat.WEBM,
                            isCancelled = isCancelled,
                            onProgress = onStepProgress,
                        )
                    }.isSuccess
                    if (!ok) {
                        exportStyledFrameBufferedVideo(
                            context, request, clips, 0, clips.size, logoBitmap, outputFile, isCancelled, onStepProgress
                        )
                    }
                }
            }
        }
    }

    /**
     * Near-instant lossless MP4 trim via MediaExtractor + MediaMuxer (zero video re-encoding).
     */
    private fun losslessStreamCopyMp4(
        context: Context,
        sourceUri: Uri,
        startMs: Long,
        endMs: Long,
        outputFile: File,
        onProgress: (Float) -> Unit,
    ): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            MediaAnalyzer.setExtractorSourceSafe(context, extractor, sourceUri)
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackMap = mutableMapOf<Int, Int>()
            var rotation = 0
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    if (mime.startsWith("video/") && fmt.containsKey(MediaFormat.KEY_ROTATION)) {
                        rotation = fmt.getInteger(MediaFormat.KEY_ROTATION)
                    }
                    extractor.selectTrack(i)
                    trackMap[i] = muxer.addTrack(fmt)
                }
            }
            if (trackMap.isEmpty()) return false
            if (rotation != 0) muxer.setOrientationHint(rotation)
            muxer.start()

            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            val durationUs = max(1L, endUs - startUs)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var basePtsUs = -1L

            while (true) {
                info.offset = 0
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break

                val sampleTime = extractor.sampleTime
                if (sampleTime > endUs) break
                val srcTrack = extractor.sampleTrackIndex
                val dstTrack = trackMap[srcTrack]
                if (dstTrack != null && sampleTime >= startUs - 500_000L) {
                    if (basePtsUs < 0L) basePtsUs = sampleTime
                    info.presentationTimeUs = (sampleTime - basePtsUs).coerceAtLeast(0L)
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(dstTrack, buffer, info)
                    onProgress(((sampleTime - startUs).toFloat() / durationUs).coerceIn(0f, 0.98f))
                }
                extractor.advance()
            }
            onProgress(1f)
            return outputFile.exists() && outputFile.length() > 1024L
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Hardware-accelerated Jetpack Media3 Transformer pipeline supporting color, crop, rotation,
     * presentation scaling, bitmap overlays (watermark, Part 1/N, logo), custom StudioAudioProcessor,
     * speed adjustment, looping background audio mix, and multi-clip Highlight Reel sequences.
     */
    @OptIn(UnstableApi::class)
    private suspend fun runMedia3Transformer(
        context: Context,
        request: ExportJobRequest,
        clips: List<ClipSegment>,
        clipIndex: Int,
        totalClips: Int,
        logoBitmap: Bitmap?,
        outputFile: File,
        audioOnly: Boolean,
        useWebm: Boolean,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ) = suspendCancellableCoroutine<Unit> { cont ->
        mainHandler.post {
            try {
                val videoEffects = buildList<Effect> {
                    if (!audioOnly) {
                        if (!request.visual.cropRect.isDefault) {
                            val c = request.visual.cropRect
                            add(
                                Crop(
                                    c.left * 2f - 1f,
                                    c.right * 2f - 1f,
                                    1f - c.bottom * 2f,
                                    1f - c.top * 2f,
                                )
                            )
                        }
                        if (request.visual.rotationDegrees != 0 || request.visual.flipHorizontal) {
                            add(
                                ScaleAndRotateTransformation.Builder()
                                    .setRotationDegrees(request.visual.rotationDegrees.toFloat())
                                    .setScale(if (request.visual.flipHorizontal) -1f else 1f, 1f)
                                    .build()
                            )
                        }
                        val targetShort = request.export.resolution.maxShortSide
                        if (targetShort != null) {
                            add(Presentation.createForHeight(targetShort))
                        } else if (request.visual.aspectPreset.ratio != null) {
                            add(
                                Presentation.createForAspectRatio(
                                    request.visual.aspectPreset.ratio,
                                    Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
                                )
                            )
                        }
                        if (abs(request.visual.contrast - 1f) > 0.01f) {
                            add(Contrast((request.visual.contrast - 1f).coerceIn(-0.8f, 0.8f)))
                        }
                        if (abs(request.visual.brightness) > 0.01f) {
                            val bScale = (1f + request.visual.brightness).coerceIn(0.4f, 1.8f)
                            add(
                                RgbAdjustment.Builder()
                                    .setRedScale(bScale)
                                    .setGreenScale(bScale)
                                    .setBlueScale(bScale)
                                    .build()
                            )
                        }
                        if (abs(request.visual.saturation - 1f) > 0.01f) {
                            val satDelta = ((request.visual.saturation - 1f) * 100f).coerceIn(-100f, 100f)
                            add(HslAdjustment.Builder().adjustSaturation(satDelta).build())
                        }
                        if (request.overlay.hasActiveOverlays) {
                            val overlayBmp = createStaticOverlayBitmap(
                                width = 720,
                                height = 1280,
                                overlay = request.overlay,
                                clipIndex = clipIndex,
                                totalClips = totalClips,
                                logoBitmap = logoBitmap,
                                clipStartMs = clips.first().startMs,
                            )
                            add(OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(overlayBmp))))
                        }
                    }
                }

                val editedItems = clips.map { clip ->
                    val mediaItem = MediaItem.Builder()
                        .setUri(request.source.uri)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(clip.startMs)
                                .setEndPositionMs(clip.endMs)
                                .build()
                        )
                        .build()

                    val audioProcessors = buildList {
                        if (abs(request.audioMotion.playbackSpeed - 1f) > 0.01f) {
                            add(SonicAudioProcessor().apply { setSpeed(request.audioMotion.playbackSpeed) })
                        }
                        if (request.audioMotion.hasAudioOrMotionEffects) {
                            add(StudioAudioProcessor(request.audioMotion, clip.durationMs))
                        }
                    }

                    EditedMediaItem.Builder(mediaItem)
                        .setRemoveVideo(audioOnly)
                        .setRemoveAudio(request.audioMotion.muted && request.audioMotion.bgTrackUri == null)
                        .setEffects(Effects(audioProcessors, videoEffects))
                        .build()
                }

                val mainSeq = EditedMediaItemSequence.Builder(editedItems).build()
                val sequences = mutableListOf(mainSeq)

                // Optional looping background music/voiceover track sequence
                if (request.audioMotion.bgTrackUri != null) {
                    val totalDurMs = clips.sumOf { it.durationMs }
                    val bgItem = EditedMediaItem.Builder(
                        MediaItem.Builder()
                            .setUri(request.audioMotion.bgTrackUri)
                            .setClippingConfiguration(
                                MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(0L)
                                    .setEndPositionMs(totalDurMs)
                                    .build()
                            )
                            .build()
                    )
                        .setRemoveVideo(true)
                        .setEffects(
                            Effects(
                                listOf(
                                    StudioAudioProcessor(
                                        AudioMotionConfig(volumeGain = request.audioMotion.bgTrackVolume),
                                        totalDurMs,
                                    )
                                ),
                                emptyList(),
                            )
                        )
                        .build()
                    sequences.add(EditedMediaItemSequence.Builder(listOf(bgItem)).setIsLooping(true).build())
                }

                val composition = Composition.Builder(sequences).build()
                val builder = Transformer.Builder(context)
                if (useWebm) {
                    builder.setVideoMimeType(MimeTypes.VIDEO_VP9)
                    builder.setAudioMimeType(MimeTypes.AUDIO_OPUS)
                } else if (audioOnly) {
                    builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
                }

                var progressRunnable: Runnable? = null
                val transformer = builder.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        progressRunnable?.let { mainHandler.removeCallbacks(it) }
                        onProgress(1f)
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        progressRunnable?.let { mainHandler.removeCallbacks(it) }
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                }).build()

                val holder = ProgressHolder()
                progressRunnable = object : Runnable {
                    override fun run() {
                        if (isCancelled()) {
                            transformer.cancel()
                            if (cont.isActive) cont.resumeWithException(InterruptedException("Cancelled"))
                            return
                        }
                        val state = transformer.getProgress(holder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress((holder.progress / 100f).coerceIn(0f, 0.99f))
                        }
                        if (cont.isActive) mainHandler.postDelayed(this, 180L)
                    }
                }

                cont.invokeOnCancellation {
                    mainHandler.post {
                        progressRunnable.let { mainHandler.removeCallbacks(it) }
                        transformer.cancel()
                    }
                }

                transformer.start(composition, outputFile.absolutePath)
                mainHandler.postDelayed(progressRunnable, 180L)
            } catch (t: Throwable) {
                if (cont.isActive) cont.resumeWithException(t)
            }
        }
    }

    /**
     * Full frame-buffered Canvas + MediaCodec H.264/AAC renderer for Reverse, Boomerang,
     * Blurred Video Backdrop, Solid Matte, Sharpen/Denoise, Video Fades, Burn-in SRT captions,
     * and Highlight Fade Dips — muxing real synchronized DSP-processed AAC audio alongside video.
     */
    private suspend fun exportStyledFrameBufferedVideo(
        context: Context,
        request: ExportJobRequest,
        clips: List<ClipSegment>,
        clipIndex: Int,
        totalClips: Int,
        logoBitmap: Bitmap?,
        outputFile: File,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ) {
        val retriever = MediaMetadataRetriever()
        val fps = 20
        val speed = request.audioMotion.playbackSpeed.coerceIn(0.5f, 2.0f)
        val shortSide = (request.export.resolution.maxShortSide ?: 720).coerceAtMost(1080)

        try {
            MediaAnalyzer.setDataSourceSafe(context, retriever, request.source.uri)

            data class FrameSample(val sourceMs: Long, val clipLocalMs: Long, val clipDurMs: Long, val clipIdx: Int, val fadeMul: Float)
            val timeline = mutableListOf<FrameSample>()
            val dipMs = (request.export.highlightFadeDipSec * 1000f).toLong()

            for ((cIdx, clip) in clips.withIndex()) {
                val outDurMs = (clip.durationMs / speed).toLong().coerceAtLeast(250L)
                val frameCount = ((outDurMs * fps) / 1000L).toInt().coerceIn(4, 1800)
                val clipSamples = (0 until frameCount).map { f ->
                    val frac = f.toFloat() / max(1, frameCount - 1)
                    val srcMs = clip.startMs + (frac * clip.durationMs).toLong()
                    val localMs = (frac * outDurMs).toLong()
                    var fade = 1f
                    if (request.visual.videoFadeInSec > 0.01f && cIdx == 0) {
                        val inMs = (request.visual.videoFadeInSec * 1000f)
                        if (localMs < inMs) fade = min(fade, localMs / inMs)
                    }
                    if (request.visual.videoFadeOutSec > 0.01f && cIdx == clips.lastIndex) {
                        val outMs = (request.visual.videoFadeOutSec * 1000f)
                        val rem = outDurMs - localMs
                        if (rem < outMs) fade = min(fade, rem / outMs)
                    }
                    if (clips.size > 1 && dipMs > 0L) {
                        if (cIdx > 0 && localMs < dipMs) fade = min(fade, localMs.toFloat() / dipMs)
                        if (cIdx < clips.lastIndex && outDurMs - localMs < dipMs) {
                            fade = min(fade, (outDurMs - localMs).toFloat() / dipMs)
                        }
                    }
                    FrameSample(srcMs, localMs, outDurMs, clipIndex + cIdx, fade.coerceIn(0f, 1f))
                }
                when {
                    request.audioMotion.boomerangEnabled -> {
                        timeline.addAll(clipSamples)
                        timeline.addAll(clipSamples.reversed())
                    }
                    request.audioMotion.reverseEnabled -> timeline.addAll(clipSamples.reversed())
                    else -> timeline.addAll(clipSamples)
                }
            }

            if (timeline.isEmpty()) throw IllegalStateException("No frames available for selected clip range.")

            // Probe first real decoded frame to lock encoder dimensions
            val firstRaw = MediaAnalyzer.extractScaledFrame(retriever, timeline.first().sourceMs * 1000L, shortSide * 2, shortSide * 2)
                ?: throw IllegalStateException("Unable to decode video frame for rendering.")
            val probe = MediaStoreHelper.renderStyledFrame(
                src = firstRaw,
                visual = request.visual,
                overlay = request.overlay,
                positionMs = timeline.first().sourceMs,
                clipIndex = timeline.first().clipIdx,
                totalClips = totalClips,
                logoBitmap = logoBitmap,
                targetShortSide = shortSide,
            )
            val outW = (probe.width / 2) * 2
            val outH = (probe.height / 2) * 2
            probe.recycle()
            firstRaw.recycle()

            // Pre-render synchronized DSP-processed PCM audio (respecting speed, reverse, boomerang, EQ, BG track)
            val processedAudio = AudioTrackExporter.renderProcessedPcm(
                context = context,
                videoUri = request.source.uri,
                clips = clips,
                config = request.audioMotion,
            )
            val includeAudio = processedAudio != null && processedAudio.samples.isNotEmpty()

            val bitrate = (3_200_000 * request.export.qualityPreset.bitrateScale).roundToInt().coerceIn(900_000, 6_000_000)
            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val audioCodec = if (includeAudio) MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC) else null
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var videoTrackIdx = -1
            var audioTrackIdx = -1
            var muxerStarted = false
            val pendingVideo = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()
            val pendingAudio = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()

            fun maybeStartMuxer() {
                val ready = videoTrackIdx >= 0 && (!includeAudio || audioTrackIdx >= 0)
                if (!muxerStarted && ready) {
                    muxer.start()
                    muxerStarted = true
                    for ((bytes, info) in pendingVideo) {
                        muxer.writeSampleData(videoTrackIdx, ByteBuffer.wrap(bytes), info)
                    }
                    pendingVideo.clear()
                    for ((bytes, info) in pendingAudio) {
                        muxer.writeSampleData(audioTrackIdx, ByteBuffer.wrap(bytes), info)
                    }
                    pendingAudio.clear()
                }
            }

            try {
                videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = videoCodec.createInputSurface()
                videoCodec.start()

                if (audioCodec != null && processedAudio != null) {
                    val audioFormat = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_AAC,
                        processedAudio.sampleRate,
                        processedAudio.channels,
                    ).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                    }
                    audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    audioCodec.start()
                }

                val vInfo = MediaCodec.BufferInfo()
                fun drainVideo(eos: Boolean) {
                    val timeout = if (eos) 10_000L else 2_000L
                    while (true) {
                        val outIdx = videoCodec.dequeueOutputBuffer(vInfo, timeout)
                        if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            if (!eos) break else continue
                        } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && videoTrackIdx < 0) {
                            videoTrackIdx = muxer.addTrack(videoCodec.outputFormat)
                            maybeStartMuxer()
                        } else if (outIdx >= 0) {
                            val buf = videoCodec.getOutputBuffer(outIdx)
                            if (buf != null && (vInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && vInfo.size > 0) {
                                buf.position(vInfo.offset)
                                buf.limit(vInfo.offset + vInfo.size)
                                val bytes = ByteArray(vInfo.size)
                                buf.get(bytes)
                                val copy = MediaCodec.BufferInfo().apply { set(0, vInfo.size, vInfo.presentationTimeUs, vInfo.flags) }
                                if (muxerStarted) muxer.writeSampleData(videoTrackIdx, ByteBuffer.wrap(bytes), copy)
                                else pendingVideo.add(bytes to copy)
                            }
                            videoCodec.releaseOutputBuffer(outIdx, false)
                            if ((vInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                        }
                    }
                }

                for ((idx, sample) in timeline.withIndex()) {
                    if (isCancelled()) throw InterruptedException("Cancelled")
                    val raw = MediaAnalyzer.extractScaledFrame(retriever, sample.sourceMs * 1000L, outW * 2, outH * 2)
                    if (raw != null) {
                        val styled = MediaStoreHelper.renderStyledFrame(
                            src = raw,
                            visual = request.visual,
                            overlay = request.overlay,
                            positionMs = sample.sourceMs,
                            clipIndex = sample.clipIdx,
                            totalClips = totalClips,
                            logoBitmap = logoBitmap,
                            targetShortSide = shortSide,
                            alphaFadeMultiplier = sample.fadeMul,
                        )
                        val canvas = surface.lockCanvas(null)
                        try {
                            canvas.drawBitmap(styled, 0f, 0f, null)
                        } finally {
                            surface.unlockCanvasAndPost(canvas)
                        }
                        styled.recycle()
                        raw.recycle()
                    }
                    drainVideo(eos = false)
                    onProgress(((idx + 1).toFloat() / timeline.size) * (if (includeAudio) 0.85f else 0.96f))
                }

                videoCodec.signalEndOfInputStream()
                drainVideo(eos = true)
                surface.release()

                // Encode & mux processed AAC audio track
                if (audioCodec != null && processedAudio != null) {
                    val aInfo = MediaCodec.BufferInfo()
                    fun drainAudio(eos: Boolean) {
                        val timeout = if (eos) 10_000L else 2_000L
                        while (true) {
                            val outIdx = audioCodec.dequeueOutputBuffer(aInfo, timeout)
                            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                                if (!eos) break else continue
                            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && audioTrackIdx < 0) {
                                audioTrackIdx = muxer.addTrack(audioCodec.outputFormat)
                                maybeStartMuxer()
                            } else if (outIdx >= 0) {
                                val buf = audioCodec.getOutputBuffer(outIdx)
                                if (buf != null && (aInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && aInfo.size > 0) {
                                    buf.position(aInfo.offset)
                                    buf.limit(aInfo.offset + aInfo.size)
                                    val bytes = ByteArray(aInfo.size)
                                    buf.get(bytes)
                                    val copy = MediaCodec.BufferInfo().apply { set(0, aInfo.size, aInfo.presentationTimeUs, aInfo.flags) }
                                    if (muxerStarted) muxer.writeSampleData(audioTrackIdx, ByteBuffer.wrap(bytes), copy)
                                    else pendingAudio.add(bytes to copy)
                                }
                                audioCodec.releaseOutputBuffer(outIdx, false)
                                if ((aInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                            }
                        }
                    }

                    val samples = processedAudio.samples
                    val ch = processedAudio.channels
                    val sr = processedAudio.sampleRate
                    val chunkShorts = 1024 * ch
                    var cursor = 0
                    while (cursor < samples.size) {
                        if (isCancelled()) throw InterruptedException("Cancelled")
                        val inIdx = audioCodec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val inBuf = audioCodec.getInputBuffer(inIdx)!!
                            inBuf.clear()
                            val count = min(chunkShorts, samples.size - cursor)
                            val framePos = cursor / ch
                            val ptsUs = (framePos * 1_000_000L) / sr
                            val sb = inBuf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            sb.put(samples, cursor, count)
                            cursor += count
                            val flags = if (cursor >= samples.size) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            audioCodec.queueInputBuffer(inIdx, 0, count * 2, ptsUs, flags)
                        }
                        drainAudio(eos = false)
                    }
                    drainAudio(eos = true)
                }

                onProgress(1f)
            } finally {
                runCatching { videoCodec.stop(); videoCodec.release() }
                runCatching { audioCodec?.stop(); audioCodec?.release() }
                if (muxerStarted) runCatching { muxer.stop() }
                runCatching { muxer.release() }
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun exportAnimatedGif(
        context: Context,
        request: ExportJobRequest,
        clips: List<ClipSegment>,
        clipIndex: Int,
        totalClips: Int,
        logoBitmap: Bitmap?,
        outputFile: File,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ) {
        val retriever = MediaMetadataRetriever()
        val gifFps = 10
        val delayMs = 1000 / gifFps
        val speed = request.audioMotion.playbackSpeed.coerceIn(0.5f, 2.0f)
        val gifShortSide = min(360, request.export.resolution.maxShortSide ?: 360)

        try {
            MediaAnalyzer.setDataSourceSafe(context, retriever, request.source.uri)
            val timestamps = mutableListOf<Pair<Long, Int>>()
            for ((cIdx, clip) in clips.withIndex()) {
                val outDurMs = (clip.durationMs / speed).toLong().coerceAtLeast(300L)
                val count = ((outDurMs * gifFps) / 1000L).toInt().coerceIn(3, 140)
                val list = (0 until count).map { f ->
                    val frac = f.toFloat() / max(1, count - 1)
                    (clip.startMs + (frac * clip.durationMs).toLong()) to (clipIndex + cIdx)
                }
                when {
                    request.audioMotion.boomerangEnabled -> {
                        timestamps.addAll(list)
                        timestamps.addAll(list.reversed())
                    }
                    request.audioMotion.reverseEnabled -> timestamps.addAll(list.reversed())
                    else -> timestamps.addAll(list)
                }
            }
            if (timestamps.isEmpty()) return

            val encoder = GifEncoder(outputFile)
            var started = false
            try {
                for ((idx, pair) in timestamps.withIndex()) {
                    if (isCancelled()) throw InterruptedException("Cancelled")
                    val raw = MediaAnalyzer.extractScaledFrame(retriever, pair.first * 1000L, gifShortSide * 2, gifShortSide * 2)
                        ?: continue
                    val styled = MediaStoreHelper.renderStyledFrame(
                        src = raw,
                        visual = request.visual,
                        overlay = request.overlay,
                        positionMs = pair.first,
                        clipIndex = pair.second,
                        totalClips = totalClips,
                        logoBitmap = logoBitmap,
                        targetShortSide = gifShortSide,
                    )
                    raw.recycle()
                    if (!started) {
                        encoder.start(styled.width, styled.height)
                        started = true
                    }
                    encoder.addFrame(styled, delayMs)
                    styled.recycle()
                    onProgress(((idx + 1).toFloat() / timestamps.size).coerceIn(0f, 0.98f))
                }
            } finally {
                encoder.finish()
            }
            onProgress(1f)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun createStaticOverlayBitmap(
        width: Int,
        height: Int,
        overlay: OverlayConfig,
        clipIndex: Int,
        totalClips: Int,
        logoBitmap: Bitmap?,
        clipStartMs: Long,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        MediaStoreHelper.drawOverlaysOnCanvas(
            canvas = canvas,
            width = width,
            height = height,
            overlay = overlay,
            positionMs = clipStartMs,
            clipIndex = clipIndex,
            totalClips = totalClips,
            logoBitmap = logoBitmap,
        )
        return bmp
    }

    private fun createPosterJpg(
        context: Context,
        request: ExportJobRequest,
        clip: ClipSegment,
        clipIndex: Int,
        totalClips: Int,
        logoBitmap: Bitmap?,
        destFile: File,
    ): File? {
        val retriever = MediaMetadataRetriever()
        return try {
            MediaAnalyzer.setDataSourceSafe(context, retriever, request.source.uri)
            val midMs = (clip.startMs + clip.endMs) / 2L
            val raw = MediaAnalyzer.extractScaledFrame(retriever, midMs * 1000L, 1080, 1080) ?: return null
            val styled = MediaStoreHelper.renderStyledFrame(
                src = raw,
                visual = request.visual,
                overlay = request.overlay,
                positionMs = midMs,
                clipIndex = clipIndex,
                totalClips = totalClips,
                logoBitmap = logoBitmap,
                targetShortSide = 720,
            )
            raw.recycle()
            FileOutputStream(destFile).use { out ->
                styled.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            styled.recycle()
            destFile
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun writeZipEntry(zos: ZipOutputStream, file: File, entryName: String) {
        zos.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { it.copyTo(zos) }
        zos.closeEntry()
    }

    private fun resolveOutputExtension(format: ExportFormat): String = when (format) {
        ExportFormat.MP3 -> "m4a" // Hardware AAC/M4A for universal Android encoding without external LAME binaries
        else -> format.extension
    }

    private fun resolveOutputMime(format: ExportFormat): String = when (format) {
        ExportFormat.MP3 -> "audio/mp4"
        else -> format.mimeType
    }
}
