package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.example.model.SourceMedia
import com.example.model.TelemetrySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class AudioAnalysisResult(
    val waveformPeaks: List<Float>,
    val rmsDb: Float,
    val quietRatio: Float,
)

object MediaAnalyzer {

    suspend fun inspectSourceMedia(context: Context, uri: Uri, isSample: Boolean = false): SourceMedia =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                setDataSourceSafe(context, retriever, uri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.coerceAtLeast(500L) ?: 10_000L
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()?.coerceAtLeast(16) ?: 1280
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()?.coerceAtLeast(16) ?: 720
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toLongOrNull()?.coerceAtLeast(300_000L) ?: 4_000_000L
                val captureRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull()?.coerceIn(12f, 120f) ?: 30f

                val (displayName, fileSize) = queryDisplayNameAndSize(context, uri)
                SourceMedia(
                    uri = uri,
                    displayName = displayName,
                    durationMs = durationMs,
                    width = width,
                    height = height,
                    rotationDegrees = rotation,
                    fileSizeBytes = fileSize,
                    bitrateBps = bitrate,
                    frameRate = captureRate,
                    isSample = isSample,
                )
            } finally {
                runCatching { retriever.release() }
            }
        }

    suspend fun extractFilmstrip(
        context: Context,
        uri: Uri,
        durationMs: Long,
        count: Int = 14,
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<Bitmap>()
        try {
            setDataSourceSafe(context, retriever, uri)
            val safeCount = count.coerceIn(4, 24)
            for (i in 0 until safeCount) {
                val tUs = ((durationMs.toDouble() * (i + 0.5) / safeCount) * 1000.0).toLong()
                val bmp = extractScaledFrame(retriever, tUs, 160, 160)
                if (bmp != null) frames.add(bmp)
            }
        } catch (_: Throwable) {
            // Gracefully return any extracted thumbnails
        } finally {
            runCatching { retriever.release() }
        }
        frames
    }

    suspend fun extractAudioWaveform(
        context: Context,
        uri: Uri,
        durationMs: Long,
        barsCount: Int = 96,
    ): AudioAnalysisResult = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        val bucketSumSq = DoubleArray(barsCount)
        val bucketCount = IntArray(barsCount)
        var totalSumSq = 0.0
        var totalSamples = 0L

        try {
            setExtractorSourceSafe(context, extractor, uri)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) {
                return@withContext AudioAnalysisResult(
                    waveformPeaks = List(barsCount) { 0.08f },
                    rmsDb = -48f,
                    quietRatio = 1f,
                )
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(audioFormat, null, null, 0)
                codec.start()

                val info = MediaCodec.BufferInfo()
                var sawInputEos = false
                var sawOutputEos = false
                val durationUs = max(1_000_000L, durationMs * 1000L)
                // For very long videos, stride samples to stay ultra-fast on mobile
                val strideUs = if (durationMs > 90_000L) (durationMs / 90L) * 1000L else 0L
                var lastSampledUs = -strideUs

                while (!sawOutputEos) {
                    if (!sawInputEos) {
                        val inIdx = codec.dequeueInputBuffer(5_000)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)!!
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                            } else {
                                val pts = extractor.sampleTime.coerceAtLeast(0L)
                                codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                if (strideUs > 0L && pts - lastSampledUs >= strideUs) {
                                    lastSampledUs = pts
                                    extractor.seekTo(pts + strideUs, MediaExtractor.SEEK_TO_NEXT_SYNC)
                                } else {
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(info, 5_000)
                    if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val shortBuf = outBuf.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val bucket = ((info.presentationTimeUs.toDouble() / durationUs) * barsCount)
                                .toInt().coerceIn(0, barsCount - 1)
                            val step = max(1, shortBuf.remaining() / 64)
                            var localSumSq = 0.0
                            var localCount = 0
                            var i = 0
                            while (i < shortBuf.remaining()) {
                                val s = shortBuf.get(i) / 32768.0
                                localSumSq += s * s
                                localCount++
                                i += step
                            }
                            bucketSumSq[bucket] += localSumSq
                            bucketCount[bucket] += localCount
                            totalSumSq += localSumSq
                            totalSamples += localCount
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEos = true
                        }
                    } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && sawInputEos) {
                        break
                    }
                }
            } finally {
                runCatching { codec.stop(); codec.release() }
            }
        } catch (_: Throwable) {
            return@withContext AudioAnalysisResult(
                waveformPeaks = List(barsCount) { 0.15f },
                rmsDb = -24f,
                quietRatio = 0.25f,
            )
        } finally {
            runCatching { extractor.release() }
        }

        val rawRms = FloatArray(barsCount) { i ->
            if (bucketCount[i] > 0) sqrt(bucketSumSq[i] / bucketCount[i]).toFloat() else 0f
        }
        val maxPeak = rawRms.maxOrNull()?.coerceAtLeast(0.01f) ?: 0.01f
        val normalized = rawRms.map { (it / maxPeak).coerceIn(0.03f, 1f) }
        val overallRms = if (totalSamples > 0) sqrt(totalSumSq / totalSamples) else 0.001
        val rmsDb = (20.0 * log10(overallRms.coerceAtLeast(1e-5))).toFloat().coerceIn(-60f, 0f)
        val quietCount = normalized.count { it < 0.16f }
        val quietRatio = quietCount.toFloat() / barsCount.coerceAtLeast(1)

        AudioAnalysisResult(
            waveformPeaks = normalized,
            rmsDb = rmsDb,
            quietRatio = quietRatio,
        )
    }

    fun detectSilenceRanges(
        waveformPeaks: List<Float>,
        durationMs: Long,
        threshold: Float,
        minSilenceMs: Long,
    ): List<LongRange> {
        if (waveformPeaks.isEmpty() || durationMs <= 0L) return emptyList()
        val barMs = durationMs.toDouble() / waveformPeaks.size
        val ranges = mutableListOf<LongRange>()
        var silentStartIdx = -1

        for (i in waveformPeaks.indices) {
            if (waveformPeaks[i] <= threshold) {
                if (silentStartIdx == -1) silentStartIdx = i
            } else {
                if (silentStartIdx != -1) {
                    val startMs = (silentStartIdx * barMs).toLong()
                    val endMs = (i * barMs).toLong()
                    if (endMs - startMs >= minSilenceMs) {
                        ranges.add(startMs..endMs)
                    }
                    silentStartIdx = -1
                }
            }
        }
        if (silentStartIdx != -1) {
            val startMs = (silentStartIdx * barMs).toLong()
            if (durationMs - startMs >= minSilenceMs) {
                ranges.add(startMs..durationMs)
            }
        }
        return ranges
    }

    suspend fun detectSceneChanges(
        context: Context,
        uri: Uri,
        durationMs: Long,
        sensitivity: Float = 0.32f,
    ): List<Long> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val cuts = mutableListOf<Long>()
        try {
            setDataSourceSafe(context, retriever, uri)
            val sampleCount = ((durationMs / 1000L).toInt() * 2).coerceIn(10, 48)
            var prevHist: FloatArray? = null
            var prevMean = 0f

            for (i in 0 until sampleCount) {
                val timeMs = (durationMs.toDouble() * i / sampleCount).toLong()
                val bmp = extractScaledFrame(retriever, timeMs * 1000L, 64, 64) ?: continue
                val (hist, meanLuma) = computeFrameSignature(bmp)
                bmp.recycle()

                if (prevHist != null) {
                    var l1Diff = 0f
                    for (b in hist.indices) {
                        l1Diff += abs(hist[b] - prevHist!![b])
                    }
                    val combinedScore = (l1Diff * 0.5f) + abs(meanLuma - prevMean) * 0.8f
                    if (combinedScore >= sensitivity && (cuts.isEmpty() || timeMs - cuts.last() >= 1500L)) {
                        if (timeMs > 800L && timeMs < durationMs - 800L) {
                            cuts.add(timeMs)
                        }
                    }
                }
                prevHist = hist
                prevMean = meanLuma
            }
        } catch (_: Throwable) {
            // Return discovered cuts so far
        } finally {
            runCatching { retriever.release() }
        }
        cuts
    }

    /**
     * Samples 6 evenly spaced keyframes and computes real pixel telemetry + audio telemetry locally.
     */
    suspend fun computeKeyframeTelemetry(
        context: Context,
        uri: Uri,
        durationMs: Long,
        audioAnalysis: AudioAnalysisResult,
    ): TelemetrySnapshot = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val lumaSamples = mutableListOf<Float>()
        val satSamples = mutableListOf<Float>()
        val edgeSamples = mutableListOf<Float>()
        val noiseSamples = mutableListOf<Float>()
        var sampledCount = 0

        try {
            setDataSourceSafe(context, retriever, uri)
            val frameFractions = listOf(0.08, 0.24, 0.42, 0.58, 0.76, 0.90)
            val hsv = FloatArray(3)

            for (frac in frameFractions) {
                val tUs = (durationMs * frac * 1000.0).toLong()
                val bmp = extractScaledFrame(retriever, tUs, 96, 96) ?: continue
                sampledCount++
                val w = bmp.width
                val h = bmp.height
                val pixels = IntArray(w * h)
                bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                bmp.recycle()

                val lumaGrid = FloatArray(w * h)
                var satSum = 0.0
                for (idx in pixels.indices) {
                    val p = pixels[idx]
                    val r = Color.red(p) / 255f
                    val g = Color.green(p) / 255f
                    val b = Color.blue(p) / 255f
                    val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    lumaGrid[idx] = y
                    lumaSamples.add(y)
                    Color.RGBToHSV(Color.red(p), Color.green(p), Color.blue(p), hsv)
                    satSum += hsv[1]
                }
                satSamples.add((satSum / pixels.size.coerceAtLeast(1)).toFloat())

                // Sobel edge gradient & dark-shadow local variance noise estimate
                var edgeSum = 0.0
                var edgeCount = 0
                var darkDiffSum = 0.0
                var darkCount = 0
                for (y in 1 until h - 1) {
                    for (x in 1 until w - 1) {
                        val c = lumaGrid[y * w + x]
                        val gx = lumaGrid[y * w + (x + 1)] - lumaGrid[y * w + (x - 1)]
                        val gy = lumaGrid[(y + 1) * w + x] - lumaGrid[(y - 1) * w + x]
                        val grad = sqrt((gx * gx + gy * gy).toDouble())
                        edgeSum += grad
                        edgeCount++
                        if (c < 0.26f) {
                            darkDiffSum += abs(c - lumaGrid[y * w + (x + 1)])
                            darkCount++
                        }
                    }
                }
                edgeSamples.add(((edgeSum / max(1, edgeCount)) * 4.5).toFloat().coerceIn(0f, 1f))
                noiseSamples.add(
                    if (darkCount > 20) ((darkDiffSum / darkCount) * 8.0).toFloat().coerceIn(0f, 1f)
                    else 0.10f
                )
            }
        } catch (_: Throwable) {
            // Fallback to partial telemetry
        } finally {
            runCatching { retriever.release() }
        }

        if (lumaSamples.isEmpty()) {
            return@withContext TelemetrySnapshot(
                sampledFrames = 0,
                audioRmsDb = audioAnalysis.rmsDb,
                quietRatio = audioAnalysis.quietRatio,
            )
        }

        lumaSamples.sort()
        val meanLuma = lumaSamples.average().toFloat()
        val p05 = lumaSamples[(lumaSamples.size * 0.05).toInt().coerceIn(0, lumaSamples.lastIndex)]
        val p95 = lumaSamples[(lumaSamples.size * 0.95).toInt().coerceIn(0, lumaSamples.lastIndex)]
        val contrastSpread = (p95 - p05).coerceIn(0f, 1f)

        TelemetrySnapshot(
            sampledFrames = sampledCount,
            meanLuma = meanLuma,
            p05Luma = p05,
            p95Luma = p95,
            contrastSpread = contrastSpread,
            meanSaturation = satSamples.average().toFloat().coerceIn(0f, 1f),
            edgeSharpness = edgeSamples.average().toFloat().coerceIn(0f, 1f),
            shadowNoise = noiseSamples.average().toFloat().coerceIn(0f, 1f),
            audioRmsDb = audioAnalysis.rmsDb,
            quietRatio = audioAnalysis.quietRatio,
        )
    }

    private fun computeFrameSignature(bmp: Bitmap): Pair<FloatArray, Float> {
        val bins = 16
        val hist = FloatArray(bins)
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        var lumaSum = 0f
        for (p in pixels) {
            val y = (0.2126f * Color.red(p) + 0.7152f * Color.green(p) + 0.0722f * Color.blue(p)) / 255f
            lumaSum += y
            val idx = min(bins - 1, (y * bins).toInt().coerceAtLeast(0))
            hist[idx] += 1f
        }
        val n = pixels.size.coerceAtLeast(1).toFloat()
        for (i in hist.indices) hist[i] /= n
        return hist to (lumaSum / n)
    }

    fun extractScaledFrame(retriever: MediaMetadataRetriever, timeUs: Long, maxW: Int, maxH: Int): Bitmap? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxW, maxH)
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { raw ->
                    val scaled = Bitmap.createScaledBitmap(raw, maxW, maxH, true)
                    if (scaled != raw) raw.recycle()
                    scaled
                }
            }
        }.getOrNull()
    }

    fun setDataSourceSafe(context: Context, retriever: MediaMetadataRetriever, uri: Uri) {
        if (uri.scheme == "file" && uri.path != null) {
            retriever.setDataSource(uri.path)
        } else {
            retriever.setDataSource(context, uri)
        }
    }

    fun setExtractorSourceSafe(context: Context, extractor: MediaExtractor, uri: Uri) {
        if (uri.scheme == "file" && uri.path != null) {
            extractor.setDataSource(uri.path!!)
        } else {
            extractor.setDataSource(context, uri, null)
        }
    }

    private fun queryDisplayNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
        if (uri.scheme == "file" && uri.path != null) {
            val f = File(uri.path!!)
            return f.name to f.length()
        }
        var name = "video.mp4"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }
}
