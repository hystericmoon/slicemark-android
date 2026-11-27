package com.example.ui.components

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.AspectPreset
import com.example.model.EditorUiState
import com.example.model.ExportFormat
import com.example.model.ExportQualityPreset
import com.example.model.ExportResolution
import com.example.model.ExportTargetMode
import com.example.model.FitMode
import com.example.model.ManualMode
import com.example.model.OverlayCorner
import com.example.model.PlatformPreset
import com.example.model.SplitMethod
import com.example.model.TrimWorkflow
import com.example.model.formatCompactDuration
import com.example.model.formatTimecode
import com.example.ui.theme.LocalStudioColors
import com.example.ui.theme.MatteSwatches
import com.example.ui.theme.StudioMonoSmall
import com.example.viewmodel.EditorViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FrameToolPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    val visual = state.visualConfig

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioCard {
            StudioSectionHeader("Aspect Ratio")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AspectPreset.entries.forEach { preset ->
                    StudioChoiceChip(
                        text = preset.label,
                        selected = visual.aspectPreset == preset,
                        onClick = { viewModel.updateVisual(recordUndo = true) { it.copy(aspectPreset = preset) } },
                    )
                }
            }

            StudioSectionHeader("Fit Mode")
            StudioSegmentedRow(
                items = FitMode.entries,
                selected = visual.fitMode,
                label = { it.label },
                onSelect = { fm -> viewModel.updateVisual(recordUndo = true) { it.copy(fitMode = fm) } },
            )

            if (visual.fitMode == FitMode.SOLID_MATTE) {
                var showCustomPicker by remember { mutableStateOf(visual.matteColorArgb !in MatteSwatches) }
                StudioSectionHeader(
                    title = "Matte Swatch",
                    trailing = String.format("#%06X", visual.matteColorArgb and 0xFFFFFF),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MatteSwatches.forEach { argb ->
                        val selected = visual.matteColorArgb == argb
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    width = if (selected) 2.5.dp else 1.dp,
                                    color = if (selected) studio.timelineAccent else studio.hairline,
                                    shape = CircleShape,
                                )
                                .clickable {
                                    viewModel.updateVisual(recordUndo = true) { it.copy(matteColorArgb = argb) }
                                },
                        )
                    }
                    Surface(
                        onClick = { showCustomPicker = !showCustomPicker },
                        shape = CircleShape,
                        color = Color(visual.matteColorArgb),
                        border = BorderStroke(
                            width = if (showCustomPicker) 2.5.dp else 1.dp,
                            color = if (showCustomPicker) studio.timelineAccent else studio.hairline,
                        ),
                        modifier = Modifier.size(30.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Palette,
                                contentDescription = "Custom matte color",
                                tint = if (AndroidColor.luminance(visual.matteColorArgb) > 0.55f) Color.Black else Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                if (showCustomPicker) {
                    val hsv = remember(visual.matteColorArgb) {
                        FloatArray(3).also { AndroidColor.colorToHSV(visual.matteColorArgb, it) }
                    }
                    var hexInput by remember(visual.matteColorArgb) {
                        mutableStateOf(String.format("#%06X", visual.matteColorArgb and 0xFFFFFF))
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = studio.surfaceWell,
                        border = BorderStroke(1.dp, studio.hairline),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedTextField(
                                    value = hexInput,
                                    onValueChange = { raw ->
                                        hexInput = raw.take(7)
                                    },
                                    label = { Text("Hex (#RRGGBB)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                Button(
                                    onClick = {
                                        val clean = hexInput.trim().let { if (it.startsWith("#")) it else "#$it" }
                                        val parsed = runCatching { AndroidColor.parseColor(clean) }.getOrNull()
                                        if (parsed != null) {
                                            viewModel.updateVisual(recordUndo = true) { it.copy(matteColorArgb = parsed) }
                                        } else {
                                            viewModel.showToast("Enter a valid hex color like #1E293B")
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                ) { Text("Set") }
                            }
                            StudioLabeledSlider(
                                label = "Hue",
                                valueText = "${hsv[0].roundToInt()}°",
                                value = hsv[0],
                                valueRange = 0f..360f,
                                onValueChange = { h ->
                                    val c = AndroidColor.HSVToColor(floatArrayOf(h, hsv[1].coerceAtLeast(0.15f), hsv[2].coerceAtLeast(0.15f)))
                                    viewModel.updateVisual { it.copy(matteColorArgb = c) }
                                },
                            )
                            StudioLabeledSlider(
                                label = "Saturation",
                                valueText = "${(hsv[1] * 100).roundToInt()}%",
                                value = hsv[1],
                                valueRange = 0f..1f,
                                onValueChange = { s ->
                                    val c = AndroidColor.HSVToColor(floatArrayOf(hsv[0], s, hsv[2]))
                                    viewModel.updateVisual { it.copy(matteColorArgb = c) }
                                },
                            )
                            StudioLabeledSlider(
                                label = "Brightness",
                                valueText = "${(hsv[2] * 100).roundToInt()}%",
                                value = hsv[2],
                                valueRange = 0f..1f,
                                onValueChange = { v ->
                                    val c = AndroidColor.HSVToColor(floatArrayOf(hsv[0], hsv[1], v))
                                    viewModel.updateVisual { it.copy(matteColorArgb = c) }
                                },
                            )
                        }
                    }
                }
            }
        }

        StudioCard {
            StudioSectionHeader("Orientation & Crop")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { viewModel.rotate90() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.RotateRight, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Rotate ${visual.rotationDegrees}°")
                }
                OutlinedButton(
                    onClick = { viewModel.toggleFlipHorizontal() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Filled.Flip, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (visual.flipHorizontal) "Flipped" else "Flip H")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { viewModel.toggleCropReticle() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.showCropReticle) studio.timelineAccent else studio.surfaceWell,
                        contentColor = if (state.showCropReticle) Color.White else MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Icon(Icons.Filled.Crop, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.showCropReticle) "Done Cropping" else "Crop Reticle")
                }
                if (!visual.cropRect.isDefault) {
                    OutlinedButton(
                        onClick = { viewModel.resetCrop() },
                        shape = RoundedCornerShape(8.dp),
                    ) { Text("Reset Crop") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextAndCaptionsToolPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onPickLogo: () -> Unit,
    onPickSubtitleFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    val overlay = state.overlayConfig

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioCard {
            StudioSectionHeader("Stamps & Watermark")
            StudioToggleRow(
                title = "Auto Part 1/N Stamp",
                subtitle = "Labels exported clips sequentially",
                checked = overlay.autoPartStamp,
                onCheckedChange = { c -> viewModel.updateOverlay(true) { it.copy(autoPartStamp = c) } },
            )
            if (overlay.autoPartStamp) {
                OutlinedTextField(
                    value = overlay.partPrefix,
                    onValueChange = { p -> viewModel.updateOverlay { it.copy(partPrefix = p) } },
                    label = { Text("Stamp Prefix") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = overlay.customWatermark,
                onValueChange = { w -> viewModel.updateOverlay { it.copy(customWatermark = w) } },
                label = { Text("Custom Text Watermark") },
                placeholder = { Text("@hahostudio") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            StudioToggleRow(
                title = "Burn Clip Timecode",
                checked = overlay.burnTimecode,
                onCheckedChange = { c -> viewModel.updateOverlay(true) { it.copy(burnTimecode = c) } },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onPickLogo,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Filled.Image, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(overlay.logoDisplayName ?: "Upload PNG / SVG Logo", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (overlay.logoUri != null) {
                    TextButton(onClick = { viewModel.updateOverlay(true) { it.copy(logoUri = null, logoDisplayName = null) } }) {
                        Text("Remove")
                    }
                }
            }

            StudioSectionHeader("Corner Placement")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OverlayCorner.entries.forEach { corner ->
                    StudioChoiceChip(
                        text = corner.label,
                        selected = overlay.corner == corner,
                        onClick = { viewModel.updateOverlay(true) { it.copy(corner = corner) } },
                    )
                }
            }

            StudioLabeledSlider(
                label = "Overlay Opacity",
                valueText = "${(overlay.opacity * 100).roundToInt()}%",
                value = overlay.opacity,
                valueRange = 0.1f..1f,
                onValueChange = { o -> viewModel.updateOverlay { it.copy(opacity = o) } },
            )
        }

        StudioCard {
            StudioSectionHeader(
                title = "Subtitles (.SRT / .VTT)",
                trailing = if (overlay.subtitles.isNotEmpty()) "${overlay.subtitles.size} cues" else null,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onPickSubtitleFile,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Filled.Subtitles, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(overlay.subtitleFileName ?: "Import .SRT / .VTT", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (overlay.subtitles.isNotEmpty()) {
                    TextButton(onClick = { viewModel.clearSubtitles() }) { Text("Clear") }
                }
            }
            if (overlay.subtitles.isNotEmpty()) {
                StudioToggleRow(
                    title = "Auto-Slice .SRT Files",
                    subtitle = "Export re-timed .srt per clip (standalone or inside ZIP)",
                    checked = overlay.includeSlicedSrt,
                    onCheckedChange = { c -> viewModel.updateOverlay(true) { it.copy(includeSlicedSrt = c) } },
                )
                StudioToggleRow(
                    title = "Burn-In Captions into Video",
                    subtitle = "Hard-render styled captions directly onto exported frames",
                    checked = overlay.burnInCaptions,
                    onCheckedChange = { c -> viewModel.updateOverlay(true) { it.copy(burnInCaptions = c) } },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioAndMotionToolPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onPickBgAudio: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val audio = state.audioMotionConfig

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioCard {
            StudioSectionHeader("Audio Mastering")
            StudioToggleRow(
                title = "Mute Original Audio",
                checked = audio.muted,
                onCheckedChange = { m -> viewModel.updateAudioMotion(true) { it.copy(muted = m) } },
            )
            StudioLabeledSlider(
                label = "Volume Gain",
                valueText = "${(audio.volumeGain * 100).roundToInt()}%",
                value = audio.volumeGain,
                valueRange = 0f..2f,
                onValueChange = { v -> viewModel.updateAudioMotion { it.copy(volumeGain = v) } },
            )
            StudioLabeledSlider(
                label = "Audio Fade-In",
                valueText = String.format("%.1fs", audio.audioFadeInSec),
                value = audio.audioFadeInSec,
                valueRange = 0f..5f,
                onValueChange = { v -> viewModel.updateAudioMotion { it.copy(audioFadeInSec = v) } },
            )
            StudioLabeledSlider(
                label = "Audio Fade-Out",
                valueText = String.format("%.1fs", audio.audioFadeOutSec),
                value = audio.audioFadeOutSec,
                valueRange = 0f..5f,
                onValueChange = { v -> viewModel.updateAudioMotion { it.copy(audioFadeOutSec = v) } },
            )
            StudioToggleRow(
                title = "Loudness Normalization",
                subtitle = "Targets broadcast-ready -16 LUFS peak balance",
                checked = audio.loudnessNormalize,
                onCheckedChange = { c -> viewModel.updateAudioMotion(true) { it.copy(loudnessNormalize = c) } },
            )
            StudioToggleRow(
                title = "Voice Clarity EQ",
                subtitle = "Highpass rumble cut + lowpass + soft speech compressor",
                checked = audio.voiceClarityEq,
                onCheckedChange = { c -> viewModel.updateAudioMotion(true) { it.copy(voiceClarityEq = c) } },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onPickBgAudio,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Filled.MusicNote, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(audio.bgTrackName ?: "Mix BG Music / Voiceover", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (audio.bgTrackUri != null) {
                    TextButton(onClick = { viewModel.updateAudioMotion(true) { it.copy(bgTrackUri = null, bgTrackName = null) } }) {
                        Text("Remove")
                    }
                }
            }
            if (audio.bgTrackUri != null) {
                StudioLabeledSlider(
                    label = "BG Track Mix Volume",
                    valueText = "${(audio.bgTrackVolume * 100).roundToInt()}%",
                    value = audio.bgTrackVolume,
                    valueRange = 0f..1.5f,
                    onValueChange = { v -> viewModel.updateAudioMotion { it.copy(bgTrackVolume = v) } },
                )
            }
        }

        StudioCard {
            StudioSectionHeader("Playback & Motion")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                    StudioChoiceChip(
                        text = "${spd}x",
                        selected = kotlin.math.abs(audio.playbackSpeed - spd) < 0.01f,
                        onClick = { viewModel.updateAudioMotion(true) { it.copy(playbackSpeed = spd) } },
                        accentSelected = true,
                    )
                }
            }
            StudioToggleRow(
                title = "Loop Active Clip",
                checked = audio.loopActiveClip,
                onCheckedChange = { c -> viewModel.updateAudioMotion(false) { it.copy(loopActiveClip = c) } },
            )
            StudioToggleRow(
                title = "Reverse Playback on Export",
                checked = audio.reverseEnabled,
                onCheckedChange = { c ->
                    viewModel.updateAudioMotion(true) { it.copy(reverseEnabled = c, boomerangEnabled = if (c) false else it.boomerangEnabled) }
                },
            )
            StudioToggleRow(
                title = "Boomerang Loop Mode",
                subtitle = "Forward then seamless reverse bounce on export",
                checked = audio.boomerangEnabled,
                onCheckedChange = { c ->
                    viewModel.updateAudioMotion(true) { it.copy(boomerangEnabled = c, reverseEnabled = if (c) false else it.reverseEnabled) }
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportToolPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onRequestExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    val exp = state.exportConfig
    val selectedCount = state.selectedClips.size

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioCard {
            StudioSectionHeader("Platform Quick Presets")
            val activePreset = detectMatchingPlatformPreset(state)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PlatformPreset.entries.chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        rowItems.forEach { preset ->
                            val selected = activePreset == preset
                            Surface(
                                onClick = { viewModel.applyPlatformPreset(preset) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) studio.timelineAccentDim else studio.surfaceWell,
                                border = BorderStroke(
                                    width = if (selected) 1.5.dp else 1.dp,
                                    color = if (selected) studio.timelineAccent else studio.hairline,
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("platform_preset_${preset.id}"),
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = preset.title,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) studio.timelineAccent else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = preset.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = studio.mutedInk,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (rowItems.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        StudioCard {
            StudioSectionHeader("Export Target")
            StudioSegmentedRow(
                items = ExportTargetMode.entries,
                selected = exp.targetMode,
                label = { it.label },
                onSelect = { tm -> viewModel.updateExportConfig { it.copy(targetMode = tm) } },
            )

            if (exp.targetMode == ExportTargetMode.MERGE_HIGHLIGHT) {
                StudioLabeledSlider(
                    label = "Highlight Fade Dip",
                    valueText = String.format("%.2fs", exp.highlightFadeDipSec),
                    value = exp.highlightFadeDipSec,
                    valueRange = 0f..1.2f,
                    onValueChange = { d -> viewModel.updateExportConfig { it.copy(highlightFadeDipSec = d) } },
                )
            }

            StudioSectionHeader("Format")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ExportFormat.entries.forEach { fmt ->
                    StudioChoiceChip(
                        text = fmt.label,
                        selected = exp.format == fmt,
                        onClick = { viewModel.updateExportConfig { it.copy(format = fmt) } },
                        accentSelected = true,
                    )
                }
            }

            if (!exp.format.isAudioOnly) {
                StudioSectionHeader("Resolution")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ExportResolution.entries.forEach { res ->
                        StudioChoiceChip(
                            text = res.label,
                            selected = exp.resolution == res,
                            onClick = { viewModel.updateExportConfig { it.copy(resolution = res) } },
                        )
                    }
                }

                StudioSectionHeader("Quality Preset")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ExportQualityPreset.entries.forEach { q ->
                        StudioChoiceChip(
                            text = q.label,
                            selected = exp.qualityPreset == q,
                            onClick = { viewModel.updateExportConfig { it.copy(qualityPreset = q) } },
                        )
                    }
                }
            }

            StudioToggleRow(
                title = "Bundle Batch as ZIP",
                subtitle = "Saves clips & sliced .SRTs directly to device when OFF",
                checked = exp.bundleAsZip,
                onCheckedChange = { b -> viewModel.updateExportConfig { it.copy(bundleAsZip = b) } },
            )
            if (!exp.format.isAudioOnly) {
                StudioToggleRow(
                    title = "Include Poster Cover JPGs",
                    checked = exp.includePosterJpgs,
                    onCheckedChange = { p -> viewModel.updateExportConfig { it.copy(includePosterJpgs = p) } },
                )
            }

            StudioBadgePill(
                text = if (state.isLosslessEligible) {
                    "Fast Lossless Stream-Copy Active"
                } else {
                    "Media3 Hardware Render Pipeline"
                },
                accent = state.isLosslessEligible,
            )

            Button(
                onClick = onRequestExport,
                enabled = !state.exportProgress.isExporting && selectedCount > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("panel_export_button"),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                val cta = when {
                    exp.targetMode == ExportTargetMode.MERGE_HIGHLIGHT -> "Merge & Export Highlight (${exp.format.label})"
                    exp.bundleAsZip && selectedCount > 1 -> "Export $selectedCount ${exp.format.label}s (.zip)"
                    else -> "Export $selectedCount ${exp.format.label}${if (selectedCount > 1) "s" else ""}"
                }
                Text(cta)
            }
        }
    }
}

@Composable
fun ClipQueuePanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onExportSingleClip: (com.example.model.ClipSegment) -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    val selectedCount = state.selectedClips.size
    val isRemoveMiddle = state.trimConfig.workflow == TrimWorkflow.MANUAL &&
        state.trimConfig.manualMode == ManualMode.REMOVE_MIDDLE
    var editingClipId by remember { mutableStateOf<String?>(null) }
    var labelDraft by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(if (isRemoveMiddle) "Cut-Out Region" else "Clip Queue", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "$selectedCount/${state.clips.size} selected • ${formatCompactDuration(state.selectedTotalDurationMs)}",
                    style = StudioMonoSmall,
                    color = studio.mutedInk,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!isRemoveMiddle && state.clips.size > 1) {
                    TextButton(
                        onClick = { viewModel.selectAllClips(selectedCount < state.clips.size) },
                    ) {
                        Text(if (selectedCount < state.clips.size) "All" else "None")
                    }
                }
                if (!isRemoveMiddle) {
                    OutlinedButton(
                        onClick = { viewModel.addManualClipAtPlayhead() },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(34.dp).testTag("add_manual_clip_button"),
                    ) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Clip", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        state.clips.forEachIndexed { idx, clip ->
            val isActive = clip.id == state.activeClip?.id
            val isEditing = editingClipId == clip.id
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { viewModel.selectClip(clip.id, seekAndPreview = true) },
                shape = RoundedCornerShape(10.dp),
                color = if (isActive) studio.surfaceElevated else MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    width = if (isActive) 1.5.dp else 1.dp,
                    color = if (isActive) studio.timelineAccent else studio.hairline,
                ),
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(
                            onClick = { viewModel.toggleClipSelection(clip.id) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = if (clip.isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                contentDescription = "Select clip",
                                tint = if (clip.isSelected) MaterialTheme.colorScheme.primary else studio.mutedInk,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isActive) studio.timelineAccent else studio.surfaceWell,
                        ) {
                            Text(
                                text = String.format("%02d", idx + 1),
                                style = StudioMonoSmall,
                                color = if (isActive) Color.White else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            if (isEditing) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    OutlinedTextField(
                                        value = labelDraft,
                                        onValueChange = { labelDraft = it },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(
                                            onDone = {
                                                viewModel.renameClip(clip.id, labelDraft)
                                                editingClipId = null
                                            }
                                        ),
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        onClick = {
                                            viewModel.renameClip(clip.id, labelDraft)
                                            editingClipId = null
                                        },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(Icons.Filled.Check, "Save label", tint = studio.success, modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = { editingClipId = null },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(Icons.Filled.Close, "Cancel rename", tint = studio.mutedInk, modifier = Modifier.size(16.dp))
                                    }
                                }
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = clip.label.ifBlank { "Clip ${idx + 1}" },
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    IconButton(
                                        onClick = {
                                            editingClipId = clip.id
                                            labelDraft = clip.label.ifBlank { "Clip ${idx + 1}" }
                                        },
                                        modifier = Modifier.size(26.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Edit,
                                            contentDescription = "Rename clip",
                                            tint = studio.mutedInk,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "${formatTimecode(clip.startMs, false)} – ${formatTimecode(clip.endMs, false)} • ${formatCompactDuration(clip.durationMs)}",
                                style = StudioMonoSmall,
                                color = studio.mutedInk,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { viewModel.selectClip(clip.id, seekAndPreview = true) },
                            modifier = Modifier.height(32.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Preview", style = MaterialTheme.typography.labelSmall)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.moveClip(clip.id, -1) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.ArrowUpward, "Move clip up", modifier = Modifier.size(15.dp))
                            }
                            IconButton(onClick = { viewModel.moveClip(clip.id, 1) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.ArrowDownward, "Move clip down", modifier = Modifier.size(15.dp))
                            }
                            IconButton(onClick = { onExportSingleClip(clip) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Download, "Export single clip", modifier = Modifier.size(15.dp))
                            }
                            IconButton(onClick = { viewModel.deleteClip(clip.id) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Delete, "Delete clip", tint = studio.danger, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun detectMatchingPlatformPreset(state: EditorUiState): PlatformPreset? {
    val t = state.trimConfig
    val e = state.exportConfig
    val v = state.visualConfig
    if (t.workflow != TrimWorkflow.BATCH_SPLIT || e.format != ExportFormat.MP4) return null
    return when {
        v.aspectPreset == AspectPreset.PORTRAIT_9_16 &&
            e.resolution == ExportResolution.P1080 &&
            e.qualityPreset == ExportQualityPreset.BALANCED &&
            t.splitMethod == SplitMethod.INTERVAL &&
            t.intervalSec == 15 -> PlatformPreset.REELS
        v.aspectPreset == AspectPreset.PORTRAIT_9_16 &&
            e.resolution == ExportResolution.P1080 &&
            e.qualityPreset == ExportQualityPreset.BALANCED &&
            t.splitMethod == SplitMethod.INTERVAL &&
            t.intervalSec == 60 -> PlatformPreset.TIKTOK
        e.resolution == ExportResolution.P720 &&
            e.qualityPreset == ExportQualityPreset.COMPACT &&
            t.splitMethod == SplitMethod.MAX_MB &&
            t.maxFileSizeMb == 16 -> PlatformPreset.WHATSAPP
        e.resolution == ExportResolution.P480 &&
            e.qualityPreset == ExportQualityPreset.COMPACT &&
            t.splitMethod == SplitMethod.MAX_MB &&
            t.maxFileSizeMb == 10 -> PlatformPreset.DISCORD
        v.aspectPreset == AspectPreset.SQUARE_1_1 &&
            e.resolution == ExportResolution.P1080 &&
            e.qualityPreset == ExportQualityPreset.BALANCED &&
            t.splitMethod == SplitMethod.INTERVAL &&
            t.intervalSec == 30 -> PlatformPreset.SQUARE
        else -> null
    }
}
