package com.example.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.TextureView
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asComposeColorFilter
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import com.example.media.MediaStoreHelper
import com.example.model.CropRectNormalized
import com.example.model.EditorUiState
import com.example.model.FitMode
import com.example.model.OverlayCorner
import com.example.model.SubtitleAndChapterParser
import com.example.model.formatTimecode
import com.example.ui.theme.LocalStudioColors
import com.example.ui.theme.StudioMonoSmall
import kotlin.math.min

@OptIn(UnstableApi::class)
@Composable
fun VideoPreviewViewport(
    state: EditorUiState,
    player: ExoPlayer,
    onToggleCompareOriginal: () -> Unit,
    onUpdateCrop: (CropRectNormalized) -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    val context = LocalContext.current
    val source = state.sourceMedia ?: return
    val visual = state.visualConfig
    val compare = state.compareOriginal

    // Compute live aspect ratio respecting rotation & preset
    val baseAspect = if (!compare && visual.rotationDegrees % 180 == 90) {
        1f / source.aspectRatio.coerceAtLeast(0.1f)
    } else {
        source.aspectRatio.coerceAtLeast(0.1f)
    }
    val targetAspect = if (compare) baseAspect else (visual.aspectPreset.ratio ?: baseAspect)

    // Live color matrix for instant 60fps preview (Compose + TextureView hardware layer)
    val liveColorFilter: ColorFilter? = remember(
        compare,
        visual.brightness,
        visual.contrast,
        visual.saturation,
    ) {
        if (compare) null
        else ColorFilter.colorMatrix(
            ColorMatrix(MediaStoreHelper.buildColorMatrix(visual).array)
        )
    }

    val svgImageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(if (studio.isDark) Color(0xFF070709) else Color(0xFFE7E7ED))
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val availW = maxWidth
        val availH = maxHeight
        val containerAspect = if (availH.value > 0f) availW.value / availH.value else 1f
        val frameModifier = if (targetAspect > containerAspect) {
            Modifier.fillMaxWidth().aspectRatio(targetAspect)
        } else {
            Modifier.height(availH).aspectRatio(targetAspect)
        }

        val backdropColor = when {
            compare -> Color.Black
            visual.fitMode == FitMode.SOLID_MATTE -> Color(visual.matteColorArgb)
            else -> Color.Black
        }

        Box(
            modifier = frameModifier
                .clip(RoundedCornerShape(12.dp))
                .background(backdropColor)
                .border(1.dp, studio.hairline, RoundedCornerShape(12.dp))
                .clipToBounds(),
        ) {
            // Optional Blurred Backdrop layer when FitMode == BLUR_BACKDROP
            if (!compare && visual.fitMode == FitMode.BLUR_BACKDROP && state.filmstripFrames.isNotEmpty()) {
                val blurFrame = state.filmstripFrames.first()
                androidx.compose.foundation.Image(
                    bitmap = blurFrame.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                renderEffect = RenderEffect.createBlurEffect(42f, 42f, Shader.TileMode.CLAMP)
                                    .asComposeRenderEffect()
                            }
                            alpha = 0.72f
                        },
                )
            }

            // Live ExoPlayer Surface (TextureView mode so Compose clip, rotation, flip, & crop apply cleanly)
            val resizeMode = if (!compare && visual.fitMode == FitMode.CROP_FILL) {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (!compare) {
                            rotationZ = visual.rotationDegrees.toFloat()
                            scaleX = if (visual.flipHorizontal) -1f else 1f
                            // Live visual crop preview magnification when crop reticle is closed
                            if (!state.showCropReticle && !visual.cropRect.isDefault) {
                                val sx = (1f / visual.cropRect.width).coerceIn(1f, 4f)
                                val sy = (1f / visual.cropRect.height).coerceIn(1f, 4f)
                                val s = min(sx, sy)
                                scaleX *= s
                                scaleY *= s
                                val cx = (visual.cropRect.left + visual.cropRect.right) / 2f - 0.5f
                                val cy = (visual.cropRect.top + visual.cropRect.bottom) / 2f - 0.5f
                                translationX = -cx * size.width * s
                                translationY = -cy * size.height * s
                            }
                        }
                    },
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            this.player = player
                            this.resizeMode = resizeMode
                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }
                    },
                    update = { pv ->
                        if (pv.player !== player) pv.player = player
                        if (pv.resizeMode != resizeMode) pv.resizeMode = resizeMode
                        // Apply live TextureView color filter when API >= 31 or software fallback tint
                        val tex = findTextureView(pv)
                        if (tex != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (compare) {
                                tex.setRenderEffect(null)
                            } else {
                                val cm = android.graphics.ColorMatrix(MediaStoreHelper.buildColorMatrix(visual).array)
                                tex.setRenderEffect(RenderEffect.createColorFilterEffect(android.graphics.ColorMatrixColorFilter(cm)))
                            }
                        }
                    },
                    onRelease = { pv ->
                        pv.player = null
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                // Lightweight color-wash preview overlay for API < 31 so brightness/contrast changes are always visible live
                if (!compare && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    val b = visual.brightness
                    if (b > 0.02f) {
                        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = (b * 0.45f).coerceIn(0f, 0.35f))))
                    } else if (b < -0.02f) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (-b * 0.65f).coerceIn(0f, 0.55f))))
                    }
                }
            }

            // Live Watermark / Part 1/N Stamp / Timecode / Logo Overlay Preview
            if (!compare) {
                LiveOverlayPreviewLayer(
                    state = state,
                    svgImageLoader = svgImageLoader,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Live SRT / VTT Subtitle Preview
            if (!compare && state.overlayConfig.subtitles.isNotEmpty()) {
                val activeCue = SubtitleAndChapterParser.activeCueAt(state.overlayConfig.subtitles, state.playheadMs)
                if (activeCue != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xC80B0B0D))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = activeCue.text,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            // Draggable Crop Reticle Overlay
            if (!compare && state.showCropReticle) {
                DraggableCropReticle(
                    crop = visual.cropRect,
                    onUpdateCrop = onUpdateCrop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Before / After "Compare Original" toggle pill when visual tuning is active
            if (visual.hasVisualAdjustments || state.overlayConfig.hasActiveOverlays) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onToggleCompareOriginal() }
                        .testTag("compare_original_button"),
                    shape = RoundedCornerShape(999.dp),
                    color = if (compare) studio.timelineAccent else Color(0xB8141417),
                    border = BorderStroke(1.dp, if (compare) studio.timelineAccent else Color(0x44FFFFFF)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Compare,
                            contentDescription = "Compare Original",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = if (compare) "ORIGINAL" else "Compare",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }
            }

            if (!compare && state.showCropReticle) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xCC141417),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Filled.Crop, null, tint = studio.timelineAccent, modifier = Modifier.size(12.dp))
                        Text("Drag handles to crop", style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveOverlayPreviewLayer(
    state: EditorUiState,
    svgImageLoader: ImageLoader,
    modifier: Modifier = Modifier,
) {
    val overlay = state.overlayConfig
    if (!overlay.hasActiveOverlays && overlay.logoUri == null) return

    val alignment = when (overlay.corner) {
        OverlayCorner.TOP_LEFT -> Alignment.TopStart
        OverlayCorner.TOP_RIGHT -> Alignment.TopEnd
        OverlayCorner.BOTTOM_LEFT -> Alignment.BottomStart
        OverlayCorner.BOTTOM_RIGHT -> Alignment.BottomEnd
    }
    val horizontalAlign = when (overlay.corner) {
        OverlayCorner.TOP_LEFT, OverlayCorner.BOTTOM_LEFT -> Alignment.Start
        OverlayCorner.TOP_RIGHT, OverlayCorner.BOTTOM_RIGHT -> Alignment.End
    }

    val lines = buildList {
        if (overlay.autoPartStamp) {
            val prefix = overlay.partPrefix.trim().ifEmpty { "Part" }
            add("$prefix ${state.activeClipIndex + 1}/${state.clips.size.coerceAtLeast(1)}")
        }
        if (overlay.customWatermark.isNotBlank()) add(overlay.customWatermark.trim())
        if (overlay.burnTimecode) add(formatTimecode(state.playheadMs))
    }

    Box(modifier = modifier.padding(10.dp)) {
        Column(
            modifier = Modifier
                .align(alignment)
                .graphicsLayer { alpha = overlay.opacity.coerceIn(0.05f, 1f) },
            horizontalAlignment = horizontalAlign,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (overlay.logoUri != null) {
                AsyncImage(
                    model = overlay.logoUri,
                    imageLoader = svgImageLoader,
                    contentDescription = "Brand logo overlay",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(44.dp),
                )
            }
            lines.forEach { text ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x990B0B0D))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = text,
                        style = StudioMonoSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun DraggableCropReticle(
    crop: CropRectNormalized,
    onUpdateCrop: (CropRectNormalized) -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    Canvas(
        modifier = modifier
            .pointerInput(crop) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val px = change.position.x / w
                    val py = change.position.y / h
                    val dx = dragAmount.x / w
                    val dy = dragAmount.y / h

                    val nearLeft = kotlin.math.abs(px - crop.left) < 0.14f
                    val nearRight = kotlin.math.abs(px - crop.right) < 0.14f
                    val nearTop = kotlin.math.abs(py - crop.top) < 0.14f
                    val nearBottom = kotlin.math.abs(py - crop.bottom) < 0.14f

                    val next = when {
                        nearLeft -> crop.copy(left = (crop.left + dx).coerceIn(0f, crop.right - 0.2f))
                        nearRight -> crop.copy(right = (crop.right + dx).coerceIn(crop.left + 0.2f, 1f))
                        nearTop -> crop.copy(top = (crop.top + dy).coerceIn(0f, crop.bottom - 0.2f))
                        nearBottom -> crop.copy(bottom = (crop.bottom + dy).coerceIn(crop.top + 0.2f, 1f))
                        else -> {
                            val cw = crop.width
                            val ch = crop.height
                            val nl = (crop.left + dx).coerceIn(0f, 1f - cw)
                            val nt = (crop.top + dy).coerceIn(0f, 1f - ch)
                            CropRectNormalized(nl, nt, nl + cw, nt + ch)
                        }
                    }
                    onUpdateCrop(next)
                }
            },
    ) {
        val l = crop.left * size.width
        val t = crop.top * size.height
        val r = crop.right * size.width
        val b = crop.bottom * size.height
        val rectW = r - l
        val rectH = b - t

        // Dim outside crop
        val dim = Color(0x88000000)
        drawRect(dim, Offset.Zero, Size(size.width, t))
        drawRect(dim, Offset(0f, b), Size(size.width, size.height - b))
        drawRect(dim, Offset(0f, t), Size(l, rectH))
        drawRect(dim, Offset(r, t), Size(size.width - r, rectH))

        // Studio Blue border & rule-of-thirds grid
        drawRect(
            color = studio.timelineAccent,
            topLeft = Offset(l, t),
            size = Size(rectW, rectH),
            style = Stroke(width = 2.dp.toPx()),
        )
        for (i in 1..2) {
            val gx = l + rectW * (i / 3f)
            val gy = t + rectH * (i / 3f)
            drawLine(Color.White.copy(alpha = 0.35f), Offset(gx, t), Offset(gx, b), strokeWidth = 1.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.35f), Offset(l, gy), Offset(r, gy), strokeWidth = 1.dp.toPx())
        }

        // Corner grab handles
        val handleR = 6.dp.toPx()
        listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b)).forEach { pt ->
            drawCircle(Color.White, radius = handleR, center = pt)
            drawCircle(studio.timelineAccent, radius = handleR, center = pt, style = Stroke(2.dp.toPx()))
        }
    }
}

private fun findTextureView(view: ViewGroup): TextureView? {
    for (i in 0 until view.childCount) {
        val child = view.getChildAt(i)
        if (child is TextureView) return child
        if (child is ViewGroup) {
            val found = findTextureView(child)
            if (found != null) return found
        }
    }
    return null
}
