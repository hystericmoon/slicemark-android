package com.example.media

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generates a real playable 18-second HD 9:16 H.264/AAC MP4 sample clip on-device with:
 * - 3 visually distinct studio scenes (for visual scene-change detection)
 * - Smooth animated graphics & tonal variation (for pixel telemetry & Auto-Tune)
 * - Audible harmonic speech-band segments separated by deliberate silence gaps (for waveform & silence jump-cut)
 */
object SampleVideoGenerator {

    private const val WIDTH = 540
    private const val HEIGHT = 960
    private const val FPS = 24
    private const val DURATION_SEC = 18
    private const val SAMPLE_RATE = 44100
    private const val CHANNEL_COUNT = 1

    suspend fun getOrCreateSampleVideo(context: Context): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "samples").apply { mkdirs() }
        val outFile = File(dir, "slicemark_studio_demo_v2.mp4")
        if (outFile.exists() && outFile.length() > 120_000L) {
            return@withContext Uri.fromFile(outFile)
        }
        if (outFile.exists()) outFile.delete()

        encodeSampleMp4(outFile)
        Uri.fromFile(outFile)
    }

    private fun encodeSampleMp4(outputFile: File) {
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_200_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)

        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var muxerStarted = false

        val pendingVideoSamples = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()
        val pendingAudioSamples = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()

        fun maybeStartMuxer() {
            if (!muxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
                muxer.start()
                muxerStarted = true
                for ((bytes, info) in pendingVideoSamples) {
                    muxer.writeSampleData(videoTrackIndex, ByteBuffer.wrap(bytes), info)
                }
                pendingVideoSamples.clear()
                for ((bytes, info) in pendingAudioSamples) {
                    muxer.writeSampleData(audioTrackIndex, ByteBuffer.wrap(bytes), info)
                }
                pendingAudioSamples.clear()
            }
        }

        try {
            videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = videoCodec.createInputSurface()
            videoCodec.start()

            audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioCodec.start()

            val totalFrames = FPS * DURATION_SEC
            val vBufferInfo = MediaCodec.BufferInfo()
            val aBufferInfo = MediaCodec.BufferInfo()

            val paintBg = Paint(Paint.ANTI_ALIAS_FLAG)
            val paintAccent = Paint(Paint.ANTI_ALIAS_FLAG)
            val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 46f
                isFakeBoldText = true
            }
            val paintSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(210, 235, 235, 245)
                textSize = 24f
            }

            // 1) Render & drain video frames
            for (frameIdx in 0 until totalFrames) {
                val canvas = inputSurface.lockCanvas(null)
                try {
                    drawDemoFrame(canvas, frameIdx, totalFrames, paintBg, paintAccent, paintText, paintSub)
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }
                drainEncoder(videoCodec, vBufferInfo, false) { data, info, newFormat ->
                    if (newFormat != null && videoTrackIndex < 0) {
                        videoTrackIndex = muxer.addTrack(newFormat)
                        maybeStartMuxer()
                    }
                    if (data != null && info != null) {
                        if (muxerStarted) {
                            muxer.writeSampleData(videoTrackIndex, ByteBuffer.wrap(data), info)
                        } else {
                            pendingVideoSamples.add(data to info)
                        }
                    }
                }
            }
            videoCodec.signalEndOfInputStream()
            drainEncoder(videoCodec, vBufferInfo, true) { data, info, newFormat ->
                if (newFormat != null && videoTrackIndex < 0) {
                    videoTrackIndex = muxer.addTrack(newFormat)
                    maybeStartMuxer()
                }
                if (data != null && info != null) {
                    if (muxerStarted) {
                        muxer.writeSampleData(videoTrackIndex, ByteBuffer.wrap(data), info)
                    } else {
                        pendingVideoSamples.add(data to info)
                    }
                }
            }
            inputSurface.release()

            // 2) Synthesize & encode audio PCM (with distinct active speech-like sections & silence gaps)
            val totalSamples = SAMPLE_RATE * DURATION_SEC
            val samplesPerChunk = 1024
            var sampleCursor = 0
            while (sampleCursor < totalSamples) {
                val inIdx = audioCodec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = audioCodec.getInputBuffer(inIdx)!!
                    inBuf.clear()
                    val count = minOf(samplesPerChunk, totalSamples - sampleCursor)
                    val ptsUs = (sampleCursor * 1_000_000L) / SAMPLE_RATE
                    for (i in 0 until count) {
                        val sIdx = sampleCursor + i
                        val sec = sIdx.toDouble() / SAMPLE_RATE
                        val sampleVal = synthesizeAudioSample(sec)
                        inBuf.putShort(sampleVal)
                    }
                    sampleCursor += count
                    val flags = if (sampleCursor >= totalSamples) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                    audioCodec.queueInputBuffer(inIdx, 0, count * 2, ptsUs, flags)
                }
                drainEncoder(audioCodec, aBufferInfo, false) { data, info, newFormat ->
                    if (newFormat != null && audioTrackIndex < 0) {
                        audioTrackIndex = muxer.addTrack(newFormat)
                        maybeStartMuxer()
                    }
                    if (data != null && info != null) {
                        if (muxerStarted) {
                            muxer.writeSampleData(audioTrackIndex, ByteBuffer.wrap(data), info)
                        } else {
                            pendingAudioSamples.add(data to info)
                        }
                    }
                }
            }
            drainEncoder(audioCodec, aBufferInfo, true) { data, info, newFormat ->
                if (newFormat != null && audioTrackIndex < 0) {
                    audioTrackIndex = muxer.addTrack(newFormat)
                    maybeStartMuxer()
                }
                if (data != null && info != null) {
                    if (muxerStarted) {
                        muxer.writeSampleData(audioTrackIndex, ByteBuffer.wrap(data), info)
                    } else {
                        pendingAudioSamples.add(data to info)
                    }
                }
            }
        } finally {
            runCatching { videoCodec.stop(); videoCodec.release() }
            runCatching { audioCodec.stop(); audioCodec.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun synthesizeAudioSample(sec: Double): Short {
        // Deliberate quiet/silence windows: 4.5..6.2s and 11.2..13.0s for silence jump-cut testing
        val isSilent = (sec in 4.5..6.2) || (sec in 11.2..13.0)
        if (isSilent) {
            return (sin(sec * 2 * PI * 90.0) * 140.0).toInt().toShort()
        }
        val envelope = 0.55 + 0.45 * sin(sec * 2 * PI * 2.2)
        val fundamental = when {
            sec < 6.0 -> 220.0
            sec < 12.0 -> 277.18
            else -> 329.63
        }
        val wave = 0.55 * sin(2 * PI * fundamental * sec) +
            0.25 * sin(2 * PI * fundamental * 2.0 * sec) +
            0.15 * sin(2 * PI * fundamental * 1.5 * sec)
        return (wave * envelope * 15000.0).toInt().coerceIn(-32767, 32767).toShort()
    }

    private fun drawDemoFrame(
        canvas: Canvas,
        frameIdx: Int,
        totalFrames: Int,
        paintBg: Paint,
        paintAccent: Paint,
        paintText: Paint,
        paintSub: Paint,
    ) {
        val sec = frameIdx.toFloat() / FPS
        val scene = when {
            sec < 6f -> 0
            sec < 12f -> 1
            else -> 2
        }

        // Distinct scene palettes (also triggers real visual scene-change detector!)
        val (topColor, botColor, accentColor, sceneTitle, sceneTag) = when (scene) {
            0 -> Tuple5(
                Color.rgb(22, 26, 36),
                Color.rgb(42, 54, 78),
                Color.rgb(75, 139, 245),
                "SCENE 01 · STUDIO INTRO",
                "Low-key ambient frame • 9:16",
            )
            1 -> Tuple5(
                Color.rgb(176, 114, 72),
                Color.rgb(92, 48, 34),
                Color.rgb(252, 211, 77),
                "SCENE 02 · GOLDEN HOUR",
                "Warm daylight contrast sweep",
            )
            else -> Tuple5(
                Color.rgb(24, 64, 58),
                Color.rgb(14, 24, 28),
                Color.rgb(52, 211, 153),
                "SCENE 03 · NIGHT MASTER",
                "Cool emerald tonal study",
            )
        }

        paintBg.shader = LinearGradient(
            0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
            topColor, botColor, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paintBg)
        paintBg.shader = null

        // Animated geometric studio rings & light sweeps
        val t = frameIdx.toFloat() / totalFrames
        val cx = WIDTH * 0.5f + cos(sec * 1.1f) * 90f
        val cy = HEIGHT * 0.42f + sin(sec * 0.9f) * 110f

        paintAccent.style = Paint.Style.FILL
        paintAccent.color = Color.argb(65, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        canvas.drawCircle(cx, cy, 175f, paintAccent)

        paintAccent.style = Paint.Style.STROKE
        paintAccent.strokeWidth = 4f
        paintAccent.color = Color.argb(180, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        canvas.drawCircle(cx, cy, 118f + sin(sec * 3f) * 14f, paintAccent)

        // Subtle studio grid lines
        paintAccent.strokeWidth = 1.5f
        paintAccent.color = Color.argb(40, 255, 255, 255)
        canvas.drawLine(48f, HEIGHT * 0.68f, WIDTH - 48f, HEIGHT * 0.68f, paintAccent)
        canvas.drawRoundRect(RectF(36f, 36f, WIDTH - 36f, HEIGHT - 36f), 28f, 28f, paintAccent)

        // Live animated audio bar indicator inside sample frame
        val isSilent = (sec in 4.5f..6.2f) || (sec in 11.2f..13.0f)
        paintAccent.style = Paint.Style.FILL
        paintAccent.color = if (isSilent) Color.argb(90, 255, 255, 255) else accentColor
        for (b in 0 until 20) {
            val bx = 64f + b * 21f
            val amp = if (isSilent) 6f else (18f + 54f * kotlin.math.abs(sin(sec * 5.2f + b * 0.55f)))
            canvas.drawRoundRect(
                RectF(bx, HEIGHT * 0.66f - amp, bx + 11f, HEIGHT * 0.66f),
                5f, 5f, paintAccent,
            )
        }

        // Studio labels
        canvas.drawText("SLICEMARK DEMO", 52f, 118f, paintSub)
        canvas.drawText(sceneTitle, 52f, HEIGHT * 0.75f, paintText)
        canvas.drawText(
            if (isSilent) "Pause / silence window (for Jump-Cut)" else sceneTag,
            52f,
            HEIGHT * 0.79f,
            paintSub,
        )
        canvas.drawText(String.format("TC  00:%05.2f  •  %03d%%", sec, (t * 100).toInt()), 52f, HEIGHT * 0.85f, paintSub)
    }

    private fun drainEncoder(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        onOutput: (ByteArray?, MediaCodec.BufferInfo?, MediaFormat?) -> Unit,
    ) {
        val timeoutUs = if (endOfStream) 10_000L else 2_000L
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    onOutput(null, null, codec.outputFormat)
                }
                outIdx >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        outBuf.get(bytes)
                        val copyInfo = MediaCodec.BufferInfo().apply {
                            set(0, info.size, info.presentationTimeUs, info.flags)
                        }
                        onOutput(bytes, copyInfo, null)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }

    private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
}
