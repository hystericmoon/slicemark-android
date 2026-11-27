package com.example.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import coil.request.SuccessResult
import android.graphics.drawable.BitmapDrawable
import com.example.model.AspectPreset
import com.example.model.FitMode
import com.example.model.OverlayConfig
import com.example.model.OverlayCorner
import com.example.model.SourceMedia
import com.example.model.SubtitleAndChapterParser
import com.example.model.VisualConfig
import com.example.model.formatTimecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object MediaStoreHelper {

    fun sanitizeDisplayName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace("..", "")
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('_')
        return base.ifBlank { "slicemark_export_${System.currentTimeMillis()}" }
    }

    fun saveFileToMediaStore(
        context: Context,
        sourceFile: File,
        displayName: String,
        mimeType: String,
        subFolder: String = "Slicemark",
    ): Uri {
        val safeName = sanitizeDisplayName(displayName)
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val (collection, baseDir) = when {
                mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
                mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MUSIC
                mimeType.startsWith("image/") && mimeType != "image/gif" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$baseDir/$subFolder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val itemUri = resolver.insert(collection, values)
            if (itemUri != null) {
                try {
                    resolver.openOutputStream(itemUri)?.use { out ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(out)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(itemUri, values, null, null)
                    return itemUri
                } catch (_: Throwable) {
                    runCatching { resolver.delete(itemUri, null, null) }
                }
            }
        }

        // Fallback for API 24-28 or restricted storage environments
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val destFile = File(exportDir, safeName)
        if (!destFile.canonicalPath.startsWith(exportDir.canonicalPath + File.separator)) {
            throw SecurityException("Invalid export path")
        }
        if (sourceFile.absolutePath != destFile.absolutePath) {
            sourceFile.copyTo(destFile, overwrite = true)
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
    }

    suspend fun loadLogoBitmap(context: Context, uri: Uri?): Bitmap? = withContext(Dispatchers.IO) {
        if (uri == null) return@withContext null
        runCatching {
            val loader = ImageLoader.Builder(context)
                .components { add(SvgDecoder.Factory()) }
                .build()
            val req = ImageRequest.Builder(context)
                .data(uri)
                .size(256)
                .allowHardware(false)
                .build()
            val result = loader.execute(req)
            if (result is SuccessResult) {
                val drawable = result.drawable
                if (drawable is BitmapDrawable) {
                    drawable.bitmap
                } else {
                    val w = drawable.intrinsicWidth.coerceIn(64, 256)
                    val h = drawable.intrinsicHeight.coerceIn(64, 256)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                        val c = Canvas(bmp)
                        drawable.setBounds(0, 0, w, h)
                        drawable.draw(c)
                    }
                }
            } else {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
        }.getOrNull()
    }

    suspend fun saveFrameSnapshotPng(
        context: Context,
        source: SourceMedia,
        positionMs: Long,
        visual: VisualConfig,
        overlay: OverlayConfig,
        clipIndex: Int,
        totalClips: Int,
        compareOriginal: Boolean,
    ): Uri? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            MediaAnalyzer.setDataSourceSafe(context, retriever, source.uri)
            val rawFrame = retriever.getFrameAtTime(positionMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.getFrameAtTime(positionMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@withContext null

            val logoBmp = if (!compareOriginal) loadLogoBitmap(context, overlay.logoUri) else null
            val rendered = if (compareOriginal) {
                rawFrame
            } else {
                renderStyledFrame(
                    src = rawFrame,
                    visual = visual,
                    overlay = overlay,
                    positionMs = positionMs,
                    clipIndex = clipIndex,
                    totalClips = totalClips,
                    logoBitmap = logoBmp,
                    targetShortSide = null,
                ).also { if (it != rawFrame) rawFrame.recycle() }
            }

            val snapDir = File(context.cacheDir, "snapshots").apply { mkdirs() }
            val name = "slicemark_frame_${System.currentTimeMillis()}.png"
            val tmp = File(snapDir, name)
            FileOutputStream(tmp).use { out ->
                rendered.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            rendered.recycle()

            saveFileToMediaStore(
                context = context,
                sourceFile = tmp,
                displayName = name,
                mimeType = "image/png",
                subFolder = "Slicemark",
            )
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Composites framing (aspect, crop, rotation, flip, blur backdrop or solid matte),
     * color grading (brightness, contrast, saturation), overlays (Part 1/N, watermark,
     * timecode, logo), and live/burn-in subtitles onto a single ARGB_8888 bitmap.
     */
    fun renderStyledFrame(
        src: Bitmap,
        visual: VisualConfig,
        overlay: OverlayConfig,
        positionMs: Long,
        clipIndex: Int,
        totalClips: Int,
        logoBitmap: Bitmap?,
        targetShortSide: Int?,
        alphaFadeMultiplier: Float = 1f,
    ): Bitmap {
        // 1) Apply crop + rotation + flip to source bitmap
        val cropL = (visual.cropRect.left * src.width).roundToInt().coerceIn(0, src.width - 2)
        val cropT = (visual.cropRect.top * src.height).roundToInt().coerceIn(0, src.height - 2)
        val cropW = (visual.cropRect.width * src.width).roundToInt().coerceIn(2, src.width - cropL)
        val cropH = (visual.cropRect.height * src.height).roundToInt().coerceIn(2, src.height - cropT)

        val transform = Matrix().apply {
            if (visual.flipHorizontal) postScale(-1f, 1f, cropW / 2f, cropH / 2f)
            if (visual.rotationDegrees != 0) postRotate(visual.rotationDegrees.toFloat())
        }
        val transformedSrc = Bitmap.createBitmap(src, cropL, cropT, cropW, cropH, transform, true)

        // 2) Resolve target aspect & output dimensions (even dimensions for H.264/VP8 encoders)
        val srcRatio = transformedSrc.width.toFloat() / transformedSrc.height.coerceAtLeast(1)
        val targetRatio = visual.aspectPreset.ratio ?: srcRatio
        val baseShort = targetShortSide ?: min(transformedSrc.width, transformedSrc.height).coerceIn(240, 1080)
        val (rawOutW, rawOutH) = if (targetRatio >= 1f) {
            (baseShort * targetRatio).roundToInt() to baseShort
        } else {
            baseShort to (baseShort / targetRatio).roundToInt()
        }
        val outW = (rawOutW / 2) * 2
        val outH = (rawOutH / 2) * 2

        val output = Bitmap.createBitmap(outW.coerceAtLeast(16), outH.coerceAtLeast(16), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // 3) Draw backdrop (Solid Matte or Blurred Video Backdrop)
        when (visual.fitMode) {
            FitMode.SOLID_MATTE -> canvas.drawColor(visual.matteColorArgb)
            FitMode.BLUR_BACKDROP -> {
                val blurred = createFastBlurBackdrop(transformedSrc, outW, outH)
                canvas.drawBitmap(blurred, 0f, 0f, null)
                blurred.recycle()
                canvas.drawColor(Color.argb(72, 0, 0, 0))
            }
            FitMode.CROP_FILL -> canvas.drawColor(Color.BLACK)
        }

        // 4) Draw color-graded video frame
        val gradePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(buildColorMatrix(visual))
        }
        val dstRect = computeFitRect(transformedSrc.width, transformedSrc.height, outW, outH, visual.fitMode)
        canvas.drawBitmap(transformedSrc, null, dstRect, gradePaint)
        if (transformedSrc != src) transformedSrc.recycle()

        // 4b) Apply real pixel Sharpen / Shadow Denoise pass when active
        if (visual.denoiseEnabled || visual.sharpen > 0.02f) {
            applyClarityPassInPlace(output, visual.sharpen, visual.denoiseEnabled)
        }

        // 5) Draw video fade dip if active
        if (alphaFadeMultiplier < 0.995f) {
            val darkAlpha = ((1f - alphaFadeMultiplier.coerceIn(0f, 1f)) * 255f).roundToInt()
            canvas.drawColor(Color.argb(darkAlpha, 0, 0, 0))
        }

        // 6) Draw overlays (Part 1/N, watermark, timecode, logo) & burn-in captions
        drawOverlaysOnCanvas(
            canvas = canvas,
            width = outW,
            height = outH,
            overlay = overlay,
            positionMs = positionMs,
            clipIndex = clipIndex,
            totalClips = totalClips,
            logoBitmap = logoBitmap,
        )

        return output
    }

    fun buildColorMatrix(visual: VisualConfig): ColorMatrix {
        val sat = ColorMatrix().apply { setSaturation(visual.saturation.coerceIn(0f, 2f)) }
        val c = visual.contrast.coerceIn(0.4f, 1.8f)
        val b = (visual.brightness.coerceIn(-0.5f, 0.5f) * 255f)
        val t = (1f - c) * 128f + b
        val cb = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        cb.preConcat(sat)
        return cb
    }

    fun drawOverlaysOnCanvas(
        canvas: Canvas,
        width: Int,
        height: Int,
        overlay: OverlayConfig,
        positionMs: Long,
        clipIndex: Int,
        totalClips: Int,
        logoBitmap: Bitmap?,
    ) {
        val alphaInt = (overlay.opacity.coerceIn(0.05f, 1f) * 255f).roundToInt()
        val margin = (min(width, height) * 0.045f).coerceAtLeast(14f)
        val textSize = (min(width, height) * 0.042f).coerceIn(18f, 44f)

        val pillLines = buildList {
            if (overlay.autoPartStamp) {
                val prefix = overlay.partPrefix.trim().ifEmpty { "Part" }
                add("$prefix ${clipIndex + 1}/${totalClips.coerceAtLeast(1)}")
            }
            if (overlay.customWatermark.isNotBlank()) {
                add(overlay.customWatermark.trim())
            }
            if (overlay.burnTimecode) {
                add(formatTimecode(positionMs, includeCentiseconds = true))
            }
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(alphaInt, 255, 255, 255)
            this.textSize = textSize
            isFakeBoldText = true
            setShadowLayer(4f, 0f, 2f, Color.argb((alphaInt * 0.7f).roundToInt(), 0, 0, 0))
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((alphaInt * 0.58f).roundToInt(), 11, 11, 13)
        }

        val isRight = overlay.corner == OverlayCorner.TOP_RIGHT || overlay.corner == OverlayCorner.BOTTOM_RIGHT
        val isBottom = overlay.corner == OverlayCorner.BOTTOM_LEFT || overlay.corner == OverlayCorner.BOTTOM_RIGHT

        var cursorY = if (isBottom) height - margin else margin

        if (logoBitmap != null) {
            val logoTarget = (min(width, height) * 0.14f).coerceIn(42f, 130f)
            val aspect = logoBitmap.width.toFloat() / logoBitmap.height.coerceAtLeast(1)
            val lw = if (aspect >= 1f) logoTarget else logoTarget * aspect
            val lh = if (aspect >= 1f) logoTarget / aspect else logoTarget
            val lx = if (isRight) width - margin - lw else margin
            val ly = if (isBottom) cursorY - lh else cursorY
            val lp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = alphaInt }
            canvas.drawBitmap(logoBitmap, null, RectF(lx, ly, lx + lw, ly + lh), lp)
            cursorY = if (isBottom) ly - 10f else ly + lh + 10f
        }

        if (pillLines.isNotEmpty()) {
            val padH = textSize * 0.55f
            val padV = textSize * 0.32f
            val lineGap = textSize * 0.25f
            val ordered = if (isBottom) pillLines.reversed() else pillLines
            for (line in ordered) {
                val tw = textPaint.measureText(line)
                val fm = textPaint.fontMetrics
                val th = fm.descent - fm.ascent
                val boxW = tw + padH * 2
                val boxH = th + padV * 2
                val bx = if (isRight) width - margin - boxW else margin
                val by = if (isBottom) cursorY - boxH else cursorY
                canvas.drawRoundRect(RectF(bx, by, bx + boxW, by + boxH), 10f, 10f, bgPaint)
                canvas.drawText(line, bx + padH, by + padV - fm.ascent, textPaint)
                cursorY = if (isBottom) by - lineGap else by + boxH + lineGap
            }
        }

        // Burn-in Subtitles (.SRT / .VTT)
        if (overlay.burnInCaptions && overlay.subtitles.isNotEmpty()) {
            val cue = SubtitleAndChapterParser.activeCueAt(overlay.subtitles, positionMs)
            if (cue != null) {
                val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    this.textSize = (min(width, height) * 0.048f).coerceIn(20f, 48f)
                    isFakeBoldText = true
                    textAlign = Paint.Align.CENTER
                }
                val subBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(185, 11, 11, 13)
                }
                val lines = cue.text.lines().take(3)
                val fm = subPaint.fontMetrics
                val lineH = fm.descent - fm.ascent + 8f
                val maxW = lines.maxOfOrNull { subPaint.measureText(it) } ?: 0f
                val totalH = lines.size * lineH + 20f
                val boxLeft = (width - maxW) / 2f - 22f
                val boxRight = (width + maxW) / 2f + 22f
                val boxBottom = height - margin * 1.4f
                val boxTop = boxBottom - totalH
                canvas.drawRoundRect(RectF(boxLeft, boxTop, boxRight, boxBottom), 12f, 12f, subBg)
                lines.forEachIndexed { idx, ln ->
                    val baseline = boxTop + 12f - fm.ascent + idx * lineH
                    canvas.drawText(ln, width / 2f, baseline, subPaint)
                }
            }
        }
    }

    private fun computeFitRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int, fitMode: FitMode): RectF {
        val srcAspect = srcW.toFloat() / srcH.coerceAtLeast(1)
        val dstAspect = dstW.toFloat() / dstH.coerceAtLeast(1)
        val scale = when (fitMode) {
            FitMode.CROP_FILL -> max(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
            FitMode.BLUR_BACKDROP, FitMode.SOLID_MATTE -> min(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        }
        val drawW = srcW * scale
        val drawH = srcH * scale
        val left = (dstW - drawW) / 2f
        val top = (dstH - drawH) / 2f
        return RectF(left, top, left + drawW, top + drawH)
    }

    private fun applyClarityPassInPlace(bitmap: Bitmap, sharpen: Float, denoise: Boolean) {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 4 || h < 4) return
        val src = IntArray(w * h)
        val dst = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        System.arraycopy(src, 0, dst, 0, src.size)
        val sharpAmt = sharpen.coerceIn(0f, 1f) * 0.65f

        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                val c = src[idx]
                val l = src[idx - 1]
                val r = src[idx + 1]
                val u = src[idx - w]
                val d = src[idx + w]

                var cr = (c shr 16) and 0xFF
                var cg = (c shr 8) and 0xFF
                var cb = c and 0xFF

                val avgR = (((l shr 16) and 0xFF) + ((r shr 16) and 0xFF) + ((u shr 16) and 0xFF) + ((d shr 16) and 0xFF)) shr 2
                val avgG = (((l shr 8) and 0xFF) + ((r shr 8) and 0xFF) + ((u shr 8) and 0xFF) + ((d shr 8) and 0xFF)) shr 2
                val avgB = ((l and 0xFF) + (r and 0xFF) + (u and 0xFF) + (d and 0xFF)) shr 2

                if (denoise) {
                    val diff = kotlin.math.abs(cr - avgR) + kotlin.math.abs(cg - avgG) + kotlin.math.abs(cb - avgB)
                    if (diff < 42) {
                        cr = (cr + avgR) shr 1
                        cg = (cg + avgG) shr 1
                        cb = (cb + avgB) shr 1
                    }
                }
                if (sharpAmt > 0.01f) {
                    cr = (cr + ((cr - avgR) * sharpAmt).roundToInt()).coerceIn(0, 255)
                    cg = (cg + ((cg - avgG) * sharpAmt).roundToInt()).coerceIn(0, 255)
                    cb = (cb + ((cb - avgB) * sharpAmt).roundToInt()).coerceIn(0, 255)
                }
                dst[idx] = (c and 0xFF000000.toInt()) or (cr shl 16) or (cg shl 8) or cb
            }
        }
        bitmap.setPixels(dst, 0, w, 0, 0, w, h)
    }

    private fun createFastBlurBackdrop(src: Bitmap, outW: Int, outH: Int): Bitmap {
        // Fast multi-pass downsample/upsample Gaussian approximation (zero RenderScript deprecation)
        val smallW = (outW / 18).coerceIn(16, 48)
        val smallH = (outH / 18).coerceIn(16, 48)
        val tiny = Bitmap.createScaledBitmap(src, smallW, smallH, true)
        val med = Bitmap.createScaledBitmap(tiny, smallW * 2, smallH * 2, true)
        tiny.recycle()
        val full = Bitmap.createScaledBitmap(med, outW, outH, true)
        med.recycle()
        return full
    }
}
