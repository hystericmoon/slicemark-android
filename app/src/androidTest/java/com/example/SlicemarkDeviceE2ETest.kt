package com.example

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.LocalPreferencesRepository
import com.example.media.ExportJobRequest
import com.example.media.MediaAnalyzer
import com.example.media.VideoExportEngine
import com.example.model.AudioMotionConfig
import com.example.model.AutoTuneEngineType
import com.example.model.ClipSegment
import com.example.model.ExportConfig
import com.example.model.ExportFormat
import com.example.model.ExportQualityPreset
import com.example.model.ExportResolution
import com.example.model.ExportTargetMode
import com.example.model.OverlayConfig
import com.example.model.SubtitleCue
import com.example.model.VisualConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
class SlicemarkDeviceE2ETest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun autoTuneDataStorePersistenceRoundTrip() = runBlocking {
        val repo = LocalPreferencesRepository(context)
        val original = repo.autoTunePrefsFlow.first()
        try {
            repo.setAutoTuneEngine(AutoTuneEngineType.GEMINI_BYOK)
            repo.setGeminiModelPreset("auto")
            repo.setGeminiCustomModel("gemini-2.5-pro")

            val updated = repo.autoTunePrefsFlow.first()
            assertEquals(AutoTuneEngineType.GEMINI_BYOK, updated.engine)
            assertEquals("auto", updated.geminiPreset)
            assertEquals("gemini-2.5-pro", updated.geminiCustomModel)
        } finally {
            repo.setAutoTuneEngine(original.engine)
            repo.setGeminiModelPreset(original.geminiPreset)
            repo.setGeminiCustomModel(original.geminiCustomModel)
        }
    }

    @Test
    fun realDeviceExportMp4WithStandaloneSrtAndWebmAndGif() = runBlocking {
        val sampleMp4 = File(context.cacheDir, "slicemark_e2e_sample_${System.currentTimeMillis()}.mp4")
        val createdUris = mutableListOf<Uri>()
        try {
            Log.d("SlicemarkE2E", "1. Generating synthetic MP4")
            generateSyntheticMp4(sampleMp4, width = 320, height = 240, durationSec = 2, fps = 15)
            Log.d("SlicemarkE2E", "2. Inspecting synthetic MP4: ${sampleMp4.length()} bytes")
            val sourceUri = Uri.fromFile(sampleMp4)
            val source = MediaAnalyzer.inspectSourceMedia(context, sourceUri)
            val clip = ClipSegment(label = "E2E_01", startMs = 0L, endMs = source.durationMs, isSelected = true)

            // 1. MP4 + standalone .SRT (bundleAsZip = false)
            Log.d("SlicemarkE2E", "3. Running MP4 + SRT export")
            val mp4Outcome = VideoExportEngine.runExportJob(
                context = context,
                request = ExportJobRequest(
                    source = source,
                    clips = listOf(clip),
                    visual = VisualConfig(),
                    overlay = OverlayConfig(
                        subtitles = listOf(SubtitleCue(1, 100L, 1600L, "E2E test caption")),
                        includeSlicedSrt = true,
                        burnInCaptions = false,
                    ),
                    audioMotion = AudioMotionConfig(),
                    export = ExportConfig(
                        targetMode = ExportTargetMode.SEPARATE_CLIPS,
                        format = ExportFormat.MP4,
                        resolution = ExportResolution.P480,
                        qualityPreset = ExportQualityPreset.COMPACT,
                        bundleAsZip = false,
                    ),
                ),
                isCancelled = { false },
                onProgress = { _, _, p, msg -> Log.d("SlicemarkE2E", "MP4 progress=$p msg=$msg") },
            )
            createdUris.addAll(mp4Outcome.savedUris)
            Log.d("SlicemarkE2E", "4. MP4 + SRT done: ${mp4Outcome.summary}")
            assertEquals("Expected 1 MP4 + 1 standalone .srt", 2, mp4Outcome.savedUris.size)
            assertTrue("Summary should mention SRT: ${mp4Outcome.summary}", mp4Outcome.summary.contains("SRT"))

            // 2. WebM export (or clean MP4 fallback if device lacks WebM encoder)
            Log.d("SlicemarkE2E", "5. Running WebM export")
            val webmOutcome = VideoExportEngine.runExportJob(
                context = context,
                request = ExportJobRequest(
                    source = source,
                    clips = listOf(clip),
                    visual = VisualConfig(),
                    overlay = OverlayConfig(),
                    audioMotion = AudioMotionConfig(),
                    export = ExportConfig(
                        targetMode = ExportTargetMode.SEPARATE_CLIPS,
                        format = ExportFormat.WEBM,
                        resolution = ExportResolution.P480,
                        qualityPreset = ExportQualityPreset.BALANCED,
                        bundleAsZip = false,
                    ),
                ),
                isCancelled = { false },
                onProgress = { _, _, p, msg -> Log.d("SlicemarkE2E", "MP4 progress=$p msg=$msg") },
            )
            createdUris.addAll(webmOutcome.savedUris)
            Log.d("SlicemarkE2E", "6. WebM done: ${webmOutcome.summary}")
            assertEquals(1, webmOutcome.savedUris.size)
            val webmUri = webmOutcome.savedUris.first()
            val header = readHeaderBytes(webmUri, 8)
            val displayName = queryDisplayName(webmUri)
            if (webmOutcome.summary.contains("saved as MP4", ignoreCase = true)) {
                assertTrue("Fallback filename must end with .mp4, got $displayName", displayName.endsWith(".mp4", ignoreCase = true))
                assertTrue(
                    "Fallback bytes must be valid MP4 (ftyp)",
                    header.size >= 8 && header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                        header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte(),
                )
            } else {
                assertTrue(
                    "WebM bytes must start with EBML header (0x1A 0x45 0xDF 0xA3)",
                    header.size >= 4 && header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                        header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte(),
                )
            }

            // 3. GIF export (12fps / 480px)
            Log.d("SlicemarkE2E", "7. Running GIF export")
            val gifOutcome = VideoExportEngine.runExportJob(
                context = context,
                request = ExportJobRequest(
                    source = source,
                    clips = listOf(clip),
                    visual = VisualConfig(),
                    overlay = OverlayConfig(),
                    audioMotion = AudioMotionConfig(muted = true),
                    export = ExportConfig(
                        targetMode = ExportTargetMode.SEPARATE_CLIPS,
                        format = ExportFormat.GIF,
                        resolution = ExportResolution.P480,
                        bundleAsZip = false,
                    ),
                ),
                isCancelled = { false },
                onProgress = { _, _, p, msg -> Log.d("SlicemarkE2E", "MP4 progress=$p msg=$msg") },
            )
            createdUris.addAll(gifOutcome.savedUris)
            Log.d("SlicemarkE2E", "8. GIF done: ${gifOutcome.summary}")
            assertEquals(1, gifOutcome.savedUris.size)
            val gifHeader = readHeaderBytes(gifOutcome.savedUris.first(), 6)
            assertEquals("GIF89a", String(gifHeader, Charsets.US_ASCII))
        } finally {
            createdUris.forEach { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            runCatching { sampleMp4.delete() }
        }
    }

    private fun readHeaderBytes(uri: Uri, count: Int): ByteArray {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(count)
            val read = input.read(buf)
            if (read <= 0) ByteArray(0) else buf.copyOf(read)
        } ?: ByteArray(0)
    }

    private fun queryDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0).orEmpty()
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun generateSyntheticMp4(file: File, width: Int, height: Int, durationSec: Int, fps: Int) {
        val totalFrames = durationSec * fps
        val sampleRate = 44100
        val channels = 1

        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 600_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }

        val vCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val aCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        var vTrack = -1
        var aTrack = -1
        var muxerStarted = false
        val pendingVideo = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()
        val pendingAudio = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()

        fun maybeStart() {
            if (!muxerStarted && vTrack >= 0 && aTrack >= 0) {
                muxer.start()
                muxerStarted = true
                pendingVideo.forEach { (b, info) -> muxer.writeSampleData(vTrack, ByteBuffer.wrap(b), info) }
                pendingVideo.clear()
                pendingAudio.forEach { (b, info) -> muxer.writeSampleData(aTrack, ByteBuffer.wrap(b), info) }
                pendingAudio.clear()
            }
        }

        try {
            vCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = vCodec.createInputSurface()
            vCodec.start()

            aCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aCodec.start()

            val vInfo = MediaCodec.BufferInfo()
            var firstVideoPtsUs = -1L
            fun drainVideo(eos: Boolean) {
                while (true) {
                    val idx = vCodec.dequeueOutputBuffer(vInfo, if (eos) 10_000L else 1_000L)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!eos) break else continue
                    } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && vTrack < 0) {
                        vTrack = muxer.addTrack(vCodec.outputFormat)
                        maybeStart()
                    } else if (idx >= 0) {
                        val buf = vCodec.getOutputBuffer(idx)
                        if (buf != null && (vInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && vInfo.size > 0) {
                            buf.position(vInfo.offset)
                            buf.limit(vInfo.offset + vInfo.size)
                            val bytes = ByteArray(vInfo.size)
                            buf.get(bytes)
                            if (firstVideoPtsUs < 0L) firstVideoPtsUs = vInfo.presentationTimeUs
                            val normPts = (vInfo.presentationTimeUs - firstVideoPtsUs).coerceAtLeast(0L)
                            val copy = MediaCodec.BufferInfo().apply { set(0, vInfo.size, normPts, vInfo.flags) }
                            if (muxerStarted) muxer.writeSampleData(vTrack, ByteBuffer.wrap(bytes), copy)
                            else pendingVideo.add(bytes to copy)
                        }
                        vCodec.releaseOutputBuffer(idx, false)
                        if ((vInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }

            val paint = Paint().apply { color = Color.WHITE; textSize = 32f }
            val frameIntervalMs = (1000L / fps).coerceAtLeast(16L)
            for (f in 0 until totalFrames) {
                val canvas = surface.lockCanvas(null)
                try {
                    canvas.drawColor(Color.rgb(20 + (f * 5) % 200, 70, 180))
                    canvas.drawText("F$f", 40f, 120f, paint)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                drainVideo(eos = false)
                Thread.sleep(frameIntervalMs)
            }
            vCodec.signalEndOfInputStream()
            drainVideo(eos = true)
            surface.release()

            val aInfo = MediaCodec.BufferInfo()
            fun drainAudio(eos: Boolean) {
                while (true) {
                    val idx = aCodec.dequeueOutputBuffer(aInfo, if (eos) 10_000L else 1_000L)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!eos) break else continue
                    } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && aTrack < 0) {
                        aTrack = muxer.addTrack(aCodec.outputFormat)
                        maybeStart()
                    } else if (idx >= 0) {
                        val buf = aCodec.getOutputBuffer(idx)
                        if (buf != null && (aInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && aInfo.size > 0) {
                            buf.position(aInfo.offset)
                            buf.limit(aInfo.offset + aInfo.size)
                            val bytes = ByteArray(aInfo.size)
                            buf.get(bytes)
                            val copy = MediaCodec.BufferInfo().apply { set(0, aInfo.size, aInfo.presentationTimeUs, aInfo.flags) }
                            if (muxerStarted) muxer.writeSampleData(aTrack, ByteBuffer.wrap(bytes), copy)
                            else pendingAudio.add(bytes to copy)
                        }
                        aCodec.releaseOutputBuffer(idx, false)
                        if ((aInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }

            val totalSamples = sampleRate * durationSec
            val samples = ShortArray(totalSamples) { idx ->
                if ((idx / 200) % 2 == 0) 3000 else -3000
            }
            var cursor = 0
            while (cursor < samples.size) {
                val inIdx = aCodec.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val inBuf = aCodec.getInputBuffer(inIdx)!!
                    inBuf.clear()
                    val count = min(1024, samples.size - cursor)
                    val ptsUs = (cursor.toLong() * 1_000_000L) / sampleRate
                    inBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, cursor, count)
                    cursor += count
                    val flags = if (cursor >= samples.size) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                    aCodec.queueInputBuffer(inIdx, 0, count * 2, ptsUs, flags)
                }
                drainAudio(eos = false)
            }
            drainAudio(eos = true)
        } finally {
            runCatching { vCodec.stop(); vCodec.release() }
            runCatching { aCodec.stop(); aCodec.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }
}
