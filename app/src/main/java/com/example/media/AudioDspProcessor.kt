package com.example.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.example.model.AudioMotionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Shared real-time PCM DSP kernel (gain 0..200%, loudness boost + soft limiter,
 * Voice Clarity EQ highpass/lowpass/compressor, and fade envelope).
 */
class RealtimeAudioDspKernel {
    private var sampleRate = 44100
    private var channels = 2
    private var hpPrevIn = FloatArray(8)
    private var hpPrevOut = FloatArray(8)
    private var lpPrevOut = FloatArray(8)
    private var envFollower = 0f

    fun configure(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate.coerceAtLeast(8000)
        this.channels = channels.coerceIn(1, 8)
        reset()
    }

    fun reset() {
        hpPrevIn = FloatArray(channels)
        hpPrevOut = FloatArray(channels)
        lpPrevOut = FloatArray(channels)
        envFollower = 0f
    }

    fun processBlock(
        inShorts: java.nio.ShortBuffer,
        outShorts: java.nio.ShortBuffer,
        sampleCount: Int,
        baseGain: Float,
        loudnessNormalize: Boolean,
        voiceClarityEq: Boolean,
        frameFadeProvider: (frameOffset: Int) -> Float,
    ) {
        val hpRc = (1.0 / (2.0 * PI * 100.0)).toFloat()
        val dt = 1f / sampleRate
        val hpAlpha = hpRc / (hpRc + dt)
        val lpRc = (1.0 / (2.0 * PI * 7500.0)).toFloat()
        val lpAlpha = dt / (lpRc + dt)
        val normBoost = if (loudnessNormalize) 1.32f else 1.0f

        var i = 0
        while (i < sampleCount) {
            val fadeMul = frameFadeProvider(i / channels)
            for (ch in 0 until channels) {
                if (i + ch >= sampleCount) break
                var x = inShorts.get(i + ch) / 32768f

                if (voiceClarityEq) {
                    val hp = hpAlpha * (hpPrevOut[ch] + x - hpPrevIn[ch])
                    hpPrevIn[ch] = x
                    hpPrevOut[ch] = hp
                    val lp = lpPrevOut[ch] + lpAlpha * (hp - lpPrevOut[ch])
                    lpPrevOut[ch] = lp
                    val level = abs(lp)
                    envFollower = if (level > envFollower) 0.92f * envFollower + 0.08f * level
                    else 0.998f * envFollower + 0.002f * level
                    val compGain = if (envFollower > 0.22f) (0.22f + (envFollower - 0.22f) * 0.45f) / envFollower else 1.18f
                    x = lp * compGain
                }

                x = x * baseGain * normBoost * fadeMul
                if (loudnessNormalize || baseGain > 1.05f) {
                    x = tanh(x.toDouble()).toFloat()
                }
                val s = (x.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
                outShorts.put(s)
            }
            i += channels
        }
    }
}

data class LivePreviewAudioState(
    val muted: Boolean = false,
    val volumeGain: Float = 1.0f,
    val loudnessNormalize: Boolean = false,
    val voiceClarityEq: Boolean = false,
    val fadeMultiplier: Float = 1.0f,
    val bypassAll: Boolean = false,
)

/**
 * Live ExoPlayer AudioProcessor reflecting Mute, 0..200% Gain, Loudness Normalize,
 * Voice Clarity EQ, and Audio Fade-In/Out in real time during preview playback.
 */
@OptIn(UnstableApi::class)
class LivePreviewAudioProcessor : BaseAudioProcessor() {
    @Volatile
    var liveState: LivePreviewAudioState = LivePreviewAudioState()

    private val kernel = RealtimeAudioDspKernel()
    private var channels = 2

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        channels = inputAudioFormat.channelCount.coerceIn(1, 8)
        kernel.configure(inputAudioFormat.sampleRate, channels)
        return inputAudioFormat
    }

    override fun onFlush() {
        kernel.reset()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val state = liveState
        val outBuf = replaceOutputBuffer(remaining)
        if (state.bypassAll) {
            outBuf.put(inputBuffer)
            outBuf.flip()
            return
        }

        val inShorts = inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val outShorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val sampleCount = inShorts.remaining()
        val baseGain = if (state.muted) 0f else state.volumeGain.coerceIn(0f, 2f)
        val fade = state.fadeMultiplier.coerceIn(0f, 1f)

        kernel.processBlock(
            inShorts = inShorts,
            outShorts = outShorts,
            sampleCount = sampleCount,
            baseGain = baseGain,
            loudnessNormalize = state.loudnessNormalize,
            voiceClarityEq = state.voiceClarityEq,
            frameFadeProvider = { fade },
        )

        inputBuffer.position(inputBuffer.limit())
        outBuf.position(0)
        outBuf.limit(remaining)
    }
}

/**
 * Custom Media3 PCM AudioProcessor providing studio gain (0..200%), fade-in/out,
 * loudness normalization, and Voice Clarity EQ (highpass + lowpass + soft compressor).
 */
@OptIn(UnstableApi::class)
class StudioAudioProcessor(
    private val config: AudioMotionConfig,
    private val clipDurationMs: Long,
) : BaseAudioProcessor() {

    private var sampleRate = 44100
    private var channels = 2
    private var processedFrames = 0L
    private var totalExpectedFrames = 1L
    private val kernel = RealtimeAudioDspKernel()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        channels = inputAudioFormat.channelCount.coerceIn(1, 8)
        totalExpectedFrames = max(1L, (clipDurationMs * sampleRate) / 1000L)
        kernel.configure(sampleRate, channels)
        return inputAudioFormat
    }

    override fun onFlush() {
        processedFrames = 0L
        kernel.reset()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val outBuf = replaceOutputBuffer(remaining)
        val inShorts = inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val outShorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val sampleCount = inShorts.remaining()

        val fadeInFrames = (config.audioFadeInSec * sampleRate).toLong()
        val fadeOutFrames = (config.audioFadeOutSec * sampleRate).toLong()
        val baseGain = if (config.muted) 0f else config.volumeGain.coerceIn(0f, 2f)

        kernel.processBlock(
            inShorts = inShorts,
            outShorts = outShorts,
            sampleCount = sampleCount,
            baseGain = baseGain,
            loudnessNormalize = config.loudnessNormalize,
            voiceClarityEq = config.voiceClarityEq,
            frameFadeProvider = { frameOffset ->
                val frameIdx = processedFrames + frameOffset
                var fadeMul = 1f
                if (fadeInFrames > 0 && frameIdx < fadeInFrames) {
                    fadeMul *= (frameIdx.toFloat() / fadeInFrames).coerceIn(0f, 1f)
                }
                if (fadeOutFrames > 0 && totalExpectedFrames > fadeOutFrames) {
                    val remFrames = totalExpectedFrames - frameIdx
                    if (remFrames < fadeOutFrames) {
                        fadeMul *= (remFrames.toFloat() / fadeOutFrames).coerceIn(0f, 1f)
                    }
                }
                fadeMul
            },
        )

        processedFrames += (sampleCount / channels)
        inputBuffer.position(inputBuffer.limit())
        outBuf.position(0)
        outBuf.limit(remaining)
    }
}

/**
 * Decodes PCM audio for a clip segment, applies studio DSP, optional looping BG music mix,
 * Reverse/Boomerang audio order, and writes a valid RIFF WAV (or audio output file).
 */
object AudioTrackExporter {

    data class RenderedAudio(
        val samples: ShortArray,
        val sampleRate: Int,
        val channels: Int,
    )

    suspend fun renderProcessedPcm(
        context: Context,
        videoUri: Uri,
        clips: List<com.example.model.ClipSegment>,
        config: AudioMotionConfig,
    ): RenderedAudio? = withContext(Dispatchers.IO) {
        if (config.muted && config.bgTrackUri == null) return@withContext null
        val speed = config.playbackSpeed.coerceIn(0.5f, 2.0f)

        var sampleRate = 44100
        var channels = 2
        val combinedList = mutableListOf<ShortArray>()
        var anyAudio = false

        for (clip in clips) {
            val decoded = decodePcmSegment(context, videoUri, clip.startMs, clip.endMs)
            if (decoded != null && decoded.samples.isNotEmpty()) {
                sampleRate = decoded.sampleRate
                channels = decoded.channels
                anyAudio = true
                var chunk = resampleSpeed(decoded.samples, channels, speed)
                if (config.boomerangEnabled) {
                    chunk = chunk + reversePcmFrames(chunk, channels)
                } else if (config.reverseEnabled) {
                    chunk = reversePcmFrames(chunk, channels)
                }
                combinedList.add(chunk)
            } else {
                val outDurSec = ((clip.durationMs / speed) / 1000.0).coerceAtLeast(0.1)
                val mul = if (config.boomerangEnabled) 2 else 1
                val silenceFrames = (outDurSec * sampleRate).toInt().coerceAtLeast(1) * mul
                combinedList.add(ShortArray(silenceFrames * channels))
            }
        }

        if (!anyAudio && config.bgTrackUri == null) return@withContext null

        val totalLen = combinedList.sumOf { it.size }
        val merged = ShortArray(totalLen)
        var offset = 0
        for (arr in combinedList) {
            System.arraycopy(arr, 0, merged, offset, arr.size)
            offset += arr.size
        }

        val bgPcm = config.bgTrackUri?.let { decodePcmSegment(context, it, 0L, Long.MAX_VALUE) }
        val processed = applyOfflineDspAndMix(
            main = merged,
            channels = channels,
            sampleRate = sampleRate,
            config = config,
            bgSamples = bgPcm?.samples,
            bgChannels = bgPcm?.channels ?: channels,
        )
        RenderedAudio(processed, sampleRate, channels)
    }

    suspend fun exportClipAudioToWav(
        context: Context,
        videoUri: Uri,
        startMs: Long,
        endMs: Long,
        config: AudioMotionConfig,
        outputWavFile: File,
    ): Boolean = exportClipsAudioToWav(
        context = context,
        videoUri = videoUri,
        clips = listOf(com.example.model.ClipSegment(startMs = startMs, endMs = endMs)),
        config = config,
        outputWavFile = outputWavFile,
    )

    suspend fun exportClipsAudioToWav(
        context: Context,
        videoUri: Uri,
        clips: List<com.example.model.ClipSegment>,
        config: AudioMotionConfig,
        outputWavFile: File,
    ): Boolean = withContext(Dispatchers.IO) {
        val totalMs = clips.sumOf { it.durationMs }.coerceAtLeast(250L)
        val rendered = renderProcessedPcm(context, videoUri, clips, config)
            ?: run {
                val frames = ((totalMs / 1000.0) * 44100).toInt().coerceAtLeast(1)
                RenderedAudio(ShortArray(frames * 2), 44100, 2)
            }
        writeWavFile(outputWavFile, rendered.samples, rendered.sampleRate, rendered.channels)
        true
    }

    suspend fun exportClipsAudioToM4a(
        context: Context,
        videoUri: Uri,
        clips: List<com.example.model.ClipSegment>,
        config: AudioMotionConfig,
        outputM4aFile: File,
    ): Boolean = withContext(Dispatchers.IO) {
        val totalMs = clips.sumOf { it.durationMs }.coerceAtLeast(250L)
        val rendered = renderProcessedPcm(context, videoUri, clips, config)
            ?: run {
                val frames = ((totalMs / 1000.0) * 44100).toInt().coerceAtLeast(1)
                RenderedAudio(ShortArray(frames * 2), 44100, 2)
            }
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            rendered.sampleRate,
            rendered.channels,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(outputM4aFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val pcmBytes = ByteArray(rendered.samples.size * 2)
            ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(rendered.samples)

            val info = MediaCodec.BufferInfo()
            var offset = 0
            var framesSent = 0L
            var inputDone = false
            var outputDone = false
            val bytesPerFrame = rendered.channels * 2

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = encoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = encoder.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        val remaining = pcmBytes.size - offset
                        if (remaining <= 0) {
                            encoder.queueInputBuffer(
                                inIdx,
                                0,
                                0,
                                (framesSent * 1_000_000L) / rendered.sampleRate,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val chunk = min(inBuf.capacity(), remaining)
                            val aligned = chunk - (chunk % bytesPerFrame)
                            inBuf.put(pcmBytes, offset, aligned)
                            val ptsUs = (framesSent * 1_000_000L) / rendered.sampleRate
                            encoder.queueInputBuffer(inIdx, 0, aligned, ptsUs, 0)
                            offset += aligned
                            framesSent += aligned / bytesPerFrame
                        }
                    }
                }

                val outIdx = encoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = encoder.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxerStarted) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, outBuf, info)
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
            true
        } finally {
            runCatching { encoder.stop(); encoder.release() }
            runCatching { if (muxerStarted) muxer.stop(); muxer.release() }
        }
    }

    private fun resampleSpeed(src: ShortArray, channels: Int, speed: Float): ShortArray {
        if (abs(speed - 1f) < 0.01f || src.isEmpty()) return src
        val inFrames = src.size / channels
        val outFrames = (inFrames / speed).roundToInt().coerceAtLeast(1)
        val out = ShortArray(outFrames * channels)
        for (f in 0 until outFrames) {
            val srcPos = f * speed
            val f0 = srcPos.toInt().coerceIn(0, inFrames - 1)
            val f1 = (f0 + 1).coerceIn(0, inFrames - 1)
            val frac = srcPos - f0
            for (c in 0 until channels) {
                val s0 = src[f0 * channels + c]
                val s1 = src[f1 * channels + c]
                out[f * channels + c] = (s0 + (s1 - s0) * frac).roundToInt().coerceIn(-32767, 32767).toShort()
            }
        }
        return out
    }

    private data class DecodedPcm(
        val samples: ShortArray,
        val sampleRate: Int,
        val channels: Int,
    )

    private fun decodePcmSegment(context: Context, uri: Uri, startMs: Long, endMs: Long): DecodedPcm? {
        val extractor = MediaExtractor()
        try {
            MediaAnalyzer.setExtractorSourceSafe(context, extractor, uri)
            var trackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    trackIdx = i
                    format = fmt
                    break
                }
            }
            if (trackIdx < 0 || format == null) return null

            extractor.selectTrack(trackIdx)
            val startUs = startMs * 1000L
            val endUs = if (endMs == Long.MAX_VALUE) Long.MAX_VALUE else endMs * 1000L
            if (startUs > 0L) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            val outBytes = ByteArrayOutputStream()

            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var sawInEos = false
                var sawOutEos = false

                while (!sawOutEos) {
                    if (!sawInEos) {
                        val inIdx = codec.dequeueInputBuffer(5_000)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(inBuf, 0)
                            val pts = extractor.sampleTime
                            if (size < 0 || (endUs != Long.MAX_VALUE && pts > endUs + 250_000L)) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInEos = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, pts, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(info, 5_000)
                    if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        val inRange = info.presentationTimeUs >= startUs &&
                            (endUs == Long.MAX_VALUE || info.presentationTimeUs <= endUs)
                        if (outBuf != null && info.size > 0 && inRange) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            outBuf.get(chunk)
                            outBytes.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutEos = true
                    } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && sawInEos) {
                        break
                    }
                }
            } finally {
                runCatching { codec.stop(); codec.release() }
            }

            val raw = outBytes.toByteArray()
            val shortCount = raw.size / 2
            val shorts = ShortArray(shortCount)
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            return DecodedPcm(shorts, sampleRate, channels)
        } catch (_: Throwable) {
            return null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun reversePcmFrames(src: ShortArray, channels: Int): ShortArray {
        val frames = src.size / channels
        val out = ShortArray(src.size)
        for (f in 0 until frames) {
            val revF = frames - 1 - f
            for (c in 0 until channels) {
                out[f * channels + c] = src[revF * channels + c]
            }
        }
        return out
    }

    private fun applyOfflineDspAndMix(
        main: ShortArray,
        channels: Int,
        sampleRate: Int,
        config: AudioMotionConfig,
        bgSamples: ShortArray?,
        bgChannels: Int,
    ): ShortArray {
        val out = ShortArray(main.size)
        val totalFrames = main.size / channels
        val fadeInFrames = (config.audioFadeInSec * sampleRate).toInt()
        val fadeOutFrames = (config.audioFadeOutSec * sampleRate).toInt()
        val mainGain = if (config.muted) 0f else config.volumeGain.coerceIn(0f, 2f)
        val bgGain = config.bgTrackVolume.coerceIn(0f, 1.5f)

        // Loudness normalization gain estimate
        var sumSq = 0.0
        for (s in main) {
            val f = s / 32768.0
            sumSq += f * f
        }
        val rms = sqrt(sumSq / max(1, main.size))
        val normGain = if (config.loudnessNormalize && rms > 0.002) {
            (0.18 / rms).toFloat().coerceIn(0.6f, 2.2f)
        } else 1.0f

        val hpPrevIn = FloatArray(channels)
        val hpPrevOut = FloatArray(channels)
        val lpPrevOut = FloatArray(channels)
        val dt = 1f / sampleRate
        val hpAlpha = (1f / (2f * PI.toFloat() * 100f)) / ((1f / (2f * PI.toFloat() * 100f)) + dt)
        val lpAlpha = dt / ((1f / (2f * PI.toFloat() * 7500f)) + dt)
        val bgFrames = if (bgSamples != null && bgSamples.isNotEmpty()) bgSamples.size / bgChannels else 0

        for (f in 0 until totalFrames) {
            var fade = 1f
            if (fadeInFrames > 0 && f < fadeInFrames) fade *= f.toFloat() / fadeInFrames
            if (fadeOutFrames > 0 && totalFrames - f < fadeOutFrames) {
                fade *= (totalFrames - f).toFloat() / fadeOutFrames
            }
            for (c in 0 until channels) {
                var x = main[f * channels + c] / 32768f
                if (config.voiceClarityEq) {
                    val hp = hpAlpha * (hpPrevOut[c] + x - hpPrevIn[c])
                    hpPrevIn[c] = x
                    hpPrevOut[c] = hp
                    val lp = lpPrevOut[c] + lpAlpha * (hp - lpPrevOut[c])
                    lpPrevOut[c] = lp
                    x = lp * 1.15f
                }
                x = x * mainGain * normGain

                if (bgSamples != null && bgFrames > 0) {
                    val bgFrame = f % bgFrames
                    val bgCh = min(c, bgChannels - 1)
                    val bgVal = bgSamples[bgFrame * bgChannels + bgCh] / 32768f
                    x += bgVal * bgGain
                }

                x = tanh((x * fade).toDouble()).toFloat()
                out[f * channels + c] = (x.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
            }
        }
        return out
    }

    private fun writeWavFile(file: File, pcm: ShortArray, sampleRate: Int, channels: Int) {
        val dataSize = pcm.size * 2
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt(36 + dataSize)
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)
                putShort(1) // PCM
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort(blockAlign.toShort())
                putShort(16) // 16-bit
                put("data".toByteArray())
                putInt(dataSize)
            }
            fos.write(header.array())

            val buf = ByteBuffer.allocate(min(65536, dataSize.coerceAtLeast(2))).order(ByteOrder.LITTLE_ENDIAN)
            var cursor = 0
            while (cursor < pcm.size) {
                buf.clear()
                val batch = min(buf.capacity() / 2, pcm.size - cursor)
                for (i in 0 until batch) buf.putShort(pcm[cursor + i])
                fos.write(buf.array(), 0, batch * 2)
                cursor += batch
            }
        }
    }
}
