package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.model.EditorUiState
import com.example.model.formatFileSize
import com.example.model.formatTimecode
import com.example.ui.theme.LocalStudioColors
import com.example.ui.theme.StudioMonoMedium
import com.example.ui.theme.StudioMonoSmall
import kotlin.math.roundToInt

@Composable
fun TransportControlStrip(
    state: EditorUiState,
    onSplit: () -> Unit,
    onMarkIn: () -> Unit,
    onMarkOut: () -> Unit,
    onSnapshot: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onJumpStart: () -> Unit,
    onStepBack: () -> Unit,
    onPlayPause: () -> Unit,
    onStepForward: () -> Unit,
    onJumpEnd: () -> Unit,
    onToggleLoop: () -> Unit,
    onToggleMute: () -> Unit,
    onCycleSpeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    val durationMs = state.sourceMedia?.durationMs ?: 0L

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, studio.hairline)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Row 1: Play / Frame-step / Jump + Timecode
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onJumpStart, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Jump to clip In", tint = MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = onStepBack, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "Step -1 frame", tint = MaterialTheme.colorScheme.onSurface)
                }
                Surface(
                    onClick = onPlayPause,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("play_pause_button"),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                IconButton(onClick = onStepForward, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "Step +1 frame", tint = MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = onJumpEnd, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Jump to clip Out", tint = MaterialTheme.colorScheme.onSurface)
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "${formatTimecode(state.playheadMs)} / ${formatTimecode(durationMs)}",
                    style = StudioMonoMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IconButton(onClick = onToggleLoop, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Repeat,
                        contentDescription = "Loop active clip",
                        tint = if (state.audioMotionConfig.loopActiveClip) studio.timelineAccent else studio.mutedInk,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onToggleMute, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (state.audioMotionConfig.muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        contentDescription = "Mute audio",
                        tint = if (state.audioMotionConfig.muted) studio.danger else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Surface(
                    onClick = onCycleSpeed,
                    shape = RoundedCornerShape(6.dp),
                    color = studio.surfaceWell,
                    border = BorderStroke(1.dp, studio.hairline),
                ) {
                    Text(
                        text = "${state.audioMotionConfig.playbackSpeed}x",
                        style = StudioMonoSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                    )
                }
            }
        }

        // Row 2: Quick Edit Rail (Split, In, Out, Snapshot, Undo, Redo)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactToolAction(
                icon = { Icon(Icons.Filled.ContentCut, null, modifier = Modifier.size(14.dp)) },
                label = "Split",
                onClick = onSplit,
                tag = "split_playhead_button",
            )
            CompactToolAction(icon = null, label = "Mark In", onClick = onMarkIn, tag = "mark_in_button")
            CompactToolAction(icon = null, label = "Mark Out", onClick = onMarkOut, tag = "mark_out_button")
            CompactToolAction(
                icon = { Icon(Icons.Filled.CameraAlt, null, modifier = Modifier.size(14.dp)) },
                label = "Frame PNG",
                onClick = onSnapshot,
                tag = "snapshot_button",
            )
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = onUndo,
                enabled = state.canUndo,
                modifier = Modifier.size(36.dp).testTag("undo_button"),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "Undo",
                    tint = if (state.canUndo) MaterialTheme.colorScheme.onSurface else studio.mutedInk.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = onRedo,
                enabled = state.canRedo,
                modifier = Modifier.size(36.dp).testTag("redo_button"),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Redo,
                    contentDescription = "Redo",
                    tint = if (state.canRedo) MaterialTheme.colorScheme.onSurface else studio.mutedInk.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun CompactToolAction(
    icon: (@Composable () -> Unit)?,
    label: String,
    onClick: () -> Unit,
    tag: String,
) {
    val studio = LocalStudioColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(7.dp),
        color = studio.surfaceWell,
        border = BorderStroke(1.dp, studio.hairline),
        modifier = Modifier
            .defaultMinSize(minHeight = 34.dp)
            .testTag(tag),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            icon?.invoke()
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun InteractiveTimelinePanel(
    state: EditorUiState,
    onScrubTo: (Long) -> Unit,
    onSelectClip: (String) -> Unit,
    onUpdateActiveClipRange: (Long, Long, Boolean) -> Unit,
    onSetZoom: (Float) -> Unit,
    onToggleSnap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    val source = state.sourceMedia ?: return
    val durationMs = source.durationMs.coerceAtLeast(500L)
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Metadata + Snap + Zoom Controls Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${source.displayName}  •  ${source.effectiveWidth}×${source.effectiveHeight}  •  ${formatFileSize(source.fileSizeBytes)}",
                style = StudioMonoSmall,
                color = studio.mutedInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    onClick = onToggleSnap,
                    shape = RoundedCornerShape(6.dp),
                    color = if (state.snapEnabled) studio.timelineAccentDim else studio.surfaceWell,
                    border = BorderStroke(1.dp, if (state.snapEnabled) studio.timelineAccent else studio.hairline),
                ) {
                    Text(
                        text = if (state.snapEnabled) "Snap ON" else "Snap",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.snapEnabled) studio.timelineAccent else studio.mutedInk,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Surface(
                    onClick = {
                        val next = when {
                            state.timelineZoom < 1.8f -> 2.0f
                            state.timelineZoom < 3.5f -> 4.0f
                            else -> 1.0f
                        }
                        onSetZoom(next)
                    },
                    shape = RoundedCornerShape(6.dp),
                    color = studio.surfaceWell,
                    border = BorderStroke(1.dp, studio.hairline),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(Icons.Filled.ZoomIn, contentDescription = "Timeline zoom", tint = studio.mutedInk, modifier = Modifier.size(13.dp))
                        Text(String.format("%.1fx", state.timelineZoom), style = StudioMonoSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }

        // Scrollable Filmstrip + Waveform + Active Clip Handles + Blue Playhead Needle
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(studio.surfaceWell)
                .border(1.dp, studio.hairline, RoundedCornerShape(8.dp))
                .pointerInput(state.timelineZoom) {
                    detectTransformGestures { _, _, zoomChange, _ ->
                        if (kotlin.math.abs(zoomChange - 1f) > 0.02f) {
                            onSetZoom(state.timelineZoom * zoomChange)
                        }
                    }
                },
        ) {
            val baseWidthDp = maxWidth
            val trackWidthDp = baseWidthDp * state.timelineZoom
            val trackWidthPx = with(density) { trackWidthDp.toPx() }.coerceAtLeast(1f)

            Box(
                modifier = Modifier
                    .horizontalScroll(scrollState)
                    .width(trackWidthDp)
                    .fillMaxHeight()
                    .pointerInput(durationMs, trackWidthPx) {
                        detectTapGestures { offset ->
                            val ms = ((offset.x / trackWidthPx) * durationMs).toLong()
                            onScrubTo(ms)
                        }
                    }
                    .pointerInput(durationMs, trackWidthPx) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            val ms = ((change.position.x / trackWidthPx) * durationMs).toLong()
                            onScrubTo(ms)
                        }
                    },
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Time ruler ticks
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp)
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        val ticks = (8 * state.timelineZoom).roundToInt().coerceIn(6, 40)
                        for (i in 0..ticks) {
                            val x = (i.toFloat() / ticks) * size.width
                            drawLine(
                                color = studio.hairline,
                                start = Offset(x, size.height * 0.4f),
                                end = Offset(x, size.height),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                    }

                    // Filmstrip Thumbnails Track
                    Box(modifier = Modifier.fillMaxWidth().height(46.dp)) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            if (state.filmstripFrames.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize().background(studio.surfaceWell),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("Generating filmstrip…", style = StudioMonoSmall, color = studio.mutedInk)
                                }
                            } else {
                                state.filmstripFrames.forEach { bmp ->
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                    )
                                }
                            }
                        }

                        // Clip segment overlays & Studio Blue active clip border + draggable In/Out handles
                        state.clips.forEachIndexed { idx, clip ->
                            val leftPx = (clip.startMs.toFloat() / durationMs) * trackWidthPx
                            val rightPx = (clip.endMs.toFloat() / durationMs) * trackWidthPx
                            val widthPx = (rightPx - leftPx).coerceAtLeast(with(density) { 18.dp.toPx() })
                            val isActive = clip.id == state.activeClip?.id

                            val leftDp = with(density) { leftPx.toDp() }
                            val widthDp = with(density) { widthPx.toDp() }

                            Box(
                                modifier = Modifier
                                    .offset(x = leftDp)
                                    .width(widthDp)
                                    .fillMaxHeight()
                                    .background(
                                        if (isActive) studio.timelineAccent.copy(alpha = 0.16f)
                                        else Color.Black.copy(alpha = 0.25f)
                                    )
                                    .border(
                                        width = if (isActive) 2.dp else 1.dp,
                                        color = if (isActive) studio.timelineAccent else Color.White.copy(alpha = 0.35f),
                                        shape = RoundedCornerShape(4.dp),
                                    )
                                    .clickable { onSelectClip(clip.id) },
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isActive) studio.timelineAccent else Color(0xB8141417),
                                    modifier = Modifier.padding(3.dp),
                                ) {
                                    Text(
                                        text = "${String.format("%02d", idx + 1)} • ${String.format("%.1fs", clip.durationMs / 1000f)}",
                                        style = StudioMonoSmall,
                                        color = Color.White,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    )
                                }

                                // Draggable Left (In) & Right (Out) Handles on Active Clip
                                if (isActive) {
                                    var draggingStart by remember(clip.id) { mutableStateOf(clip.startMs) }
                                    var draggingEnd by remember(clip.id) { mutableStateOf(clip.endMs) }
                                    // Left Handle
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.CenterStart)
                                            .width(14.dp)
                                            .fillMaxHeight()
                                            .background(studio.timelineAccent, RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp))
                                            .pointerInput(clip.id, trackWidthPx) {
                                                detectDragGestures(
                                                    onDragStart = {
                                                        draggingStart = clip.startMs
                                                        draggingEnd = clip.endMs
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        val dMs = ((dragAmount.x / trackWidthPx) * durationMs).toLong()
                                                        draggingStart = (draggingStart + dMs).coerceIn(0L, draggingEnd - 300L)
                                                        onUpdateActiveClipRange(draggingStart, draggingEnd, false)
                                                    },
                                                    onDragEnd = {
                                                        onUpdateActiveClipRange(draggingStart, draggingEnd, true)
                                                    },
                                                )
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Box(Modifier.width(2.dp).height(18.dp).background(Color.White, RoundedCornerShape(1.dp)))
                                    }

                                    // Right Handle
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .width(14.dp)
                                            .fillMaxHeight()
                                            .background(studio.timelineAccent, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                                            .pointerInput(clip.id, trackWidthPx) {
                                                detectDragGestures(
                                                    onDragStart = {
                                                        draggingStart = clip.startMs
                                                        draggingEnd = clip.endMs
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        val dMs = ((dragAmount.x / trackWidthPx) * durationMs).toLong()
                                                        draggingEnd = (draggingEnd + dMs).coerceIn(draggingStart + 300L, durationMs)
                                                        onUpdateActiveClipRange(draggingStart, draggingEnd, false)
                                                    },
                                                    onDragEnd = {
                                                        onUpdateActiveClipRange(draggingStart, draggingEnd, true)
                                                    },
                                                )
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Box(Modifier.width(2.dp).height(18.dp).background(Color.White, RoundedCornerShape(1.dp)))
                                    }
                                }
                            }
                        }
                    }

                    // Audio Waveform Bar Track + Silence Shading + Scene Markers
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(30.dp)
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f)),
                    ) {
                        // Highlight detected silence gaps subtly
                        state.detectedSilenceRanges.forEach { range ->
                            val sx = (range.first.toFloat() / durationMs) * size.width
                            val ex = (range.last.toFloat() / durationMs) * size.width
                            drawRect(
                                color = studio.danger.copy(alpha = 0.22f),
                                topLeft = Offset(sx, 0f),
                                size = Size((ex - sx).coerceAtLeast(2f), size.height),
                            )
                        }

                        // Waveform peaks
                        val peaks = state.waveformPeaks
                        if (peaks.isNotEmpty()) {
                            val step = size.width / peaks.size
                            val barW = (step * 0.68f).coerceAtLeast(1.5f)
                            val activeClip = state.activeClip
                            peaks.forEachIndexed { idx, amp ->
                                val barMs = ((idx + 0.5f) / peaks.size) * durationMs
                                val inActive = activeClip != null && barMs >= activeClip.startMs && barMs <= activeClip.endMs
                                val h = (amp * size.height * 0.86f).coerceAtLeast(2.dp.toPx())
                                val x = idx * step
                                drawRect(
                                    color = if (inActive) studio.timelineAccent else studio.mutedInk.copy(alpha = 0.55f),
                                    topLeft = Offset(x, size.height - h),
                                    size = Size(barW, h),
                                )
                            }
                        }

                        // Scene change ticks
                        state.detectedSceneTimestampsMs.forEach { ts ->
                            val x = (ts.toFloat() / durationMs) * size.width
                            drawLine(
                                color = studio.warning,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 1.5.dp.toPx(),
                            )
                        }
                    }
                }

                // Studio Blue Playhead Needle
                val playheadX = ((state.playheadMs.toFloat() / durationMs) * trackWidthPx).roundToInt()
                Box(
                    modifier = Modifier
                        .offset { IntOffset(playheadX - 6, 0) }
                        .width(12.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(studio.timelineAccent, CircleShape)
                            .border(1.5.dp, Color.White, CircleShape),
                    )
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(studio.timelineAccent),
                    )
                }
            }
        }
    }
}
