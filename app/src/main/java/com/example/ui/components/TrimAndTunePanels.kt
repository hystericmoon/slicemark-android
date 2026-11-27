package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.Topic
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.autotune.ModelPresetOption
import com.example.autotune.SmartAutoTuneEngine
import com.example.model.AutoTuneEngineType
import com.example.model.AutoTuneGoal
import com.example.model.EditorUiState
import com.example.model.ManualMode
import com.example.model.RemainderPolicy
import com.example.model.SplitMethod
import com.example.model.TrimConfig
import com.example.model.TrimWorkflow
import com.example.model.formatTimecode
import com.example.ui.theme.LocalStudioColors
import com.example.ui.theme.StudioMonoMedium
import com.example.ui.theme.StudioMonoSmall
import com.example.viewmodel.EditorViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrimToolPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onOpenChaptersModal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    val trim = state.trimConfig
    val active = state.activeClip

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StudioCard {
            StudioSectionHeader("Trim Workflow")
            StudioSegmentedRow(
                items = TrimWorkflow.entries,
                selected = trim.workflow,
                label = { it.label },
                onSelect = { wf -> viewModel.updateTrimConfig(recalculateClips = true) { it.copy(workflow = wf) } },
            )

            when (trim.workflow) {
                TrimWorkflow.BATCH_SPLIT -> {
                    StudioSectionHeader("Split Method")
                    StudioSegmentedRow(
                        items = SplitMethod.entries,
                        selected = trim.splitMethod,
                        label = { it.label },
                        onSelect = { m -> viewModel.updateTrimConfig(recalculateClips = true) { it.copy(splitMethod = m) } },
                    )

                    when (trim.splitMethod) {
                        SplitMethod.INTERVAL -> {
                            var customSec by remember(trim.intervalSec) { mutableStateOf(trim.intervalSec.toString()) }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                listOf(15, 30, 45, 60, 90, 120).forEach { sec ->
                                    StudioChoiceChip(
                                        text = "${sec}s",
                                        selected = trim.intervalSec == sec,
                                        onClick = { viewModel.updateTrimConfig(recalculateClips = true) { it.copy(intervalSec = sec) } },
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedTextField(
                                    value = customSec,
                                    onValueChange = { customSec = it.filter(Char::isDigit).take(4) },
                                    label = { Text("Custom sec") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f),
                                )
                                Button(
                                    onClick = {
                                        val v = customSec.toIntOrNull()?.coerceIn(3, 600) ?: 15
                                        viewModel.updateTrimConfig(recalculateClips = true) { it.copy(intervalSec = v) }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                ) { Text("Apply") }
                            }
                        }

                        SplitMethod.N_PARTS -> {
                            var customParts by remember(trim.targetPartCount) { mutableStateOf(trim.targetPartCount.toString()) }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                listOf(2, 3, 4, 5, 6, 8, 10, 12).forEach { n ->
                                    StudioChoiceChip(
                                        text = "$n Parts",
                                        selected = trim.targetPartCount == n,
                                        onClick = { viewModel.updateTrimConfig(recalculateClips = true) { it.copy(targetPartCount = n) } },
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedTextField(
                                    value = customParts,
                                    onValueChange = { customParts = it.filter(Char::isDigit).take(2) },
                                    label = { Text("Custom parts (2–50)") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f),
                                )
                                Button(
                                    onClick = {
                                        val v = customParts.toIntOrNull()?.coerceIn(2, 50) ?: 4
                                        viewModel.updateTrimConfig(recalculateClips = true) { it.copy(targetPartCount = v) }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                ) { Text("Split") }
                            }
                        }

                        SplitMethod.MAX_MB -> {
                            var customMb by remember(trim.maxFileSizeMb) { mutableStateOf(trim.maxFileSizeMb.toString()) }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                listOf(
                                    10 to "10 MB (Discord)",
                                    16 to "16 MB (WhatsApp)",
                                    25 to "25 MB",
                                    50 to "50 MB",
                                ).forEach { (mb, label) ->
                                    StudioChoiceChip(
                                        text = label,
                                        selected = trim.maxFileSizeMb == mb,
                                        onClick = { viewModel.updateTrimConfig(recalculateClips = true) { it.copy(maxFileSizeMb = mb) } },
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedTextField(
                                    value = customMb,
                                    onValueChange = { customMb = it.filter(Char::isDigit).take(3) },
                                    label = { Text("Custom max MB (1–500)") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f),
                                )
                                Button(
                                    onClick = {
                                        val v = customMb.toIntOrNull()?.coerceIn(1, 500) ?: 10
                                        viewModel.updateTrimConfig(recalculateClips = true) { it.copy(maxFileSizeMb = v) }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                ) { Text("Apply") }
                            }
                        }
                    }

                    StudioToggleRow(
                        title = "Snap Cuts to Silence",
                        subtitle = "Avoid chopping mid-sentence (±3s)",
                        checked = trim.snapToSilence,
                        onCheckedChange = { checked ->
                            viewModel.updateTrimConfig(recalculateClips = true) { it.copy(snapToSilence = checked) }
                        },
                    )

                    StudioLabeledSlider(
                        label = "Clip Overlap (Pre-Roll)",
                        valueText = String.format("%.1fs", trim.overlapPreRollSec),
                        value = trim.overlapPreRollSec,
                        valueRange = 0f..3.0f,
                        onValueChange = { v ->
                            viewModel.updateTrimConfig(recalculateClips = true) { it.copy(overlapPreRollSec = v) }
                        },
                    )

                    StudioSectionHeader("Trailing Remainder")
                    StudioSegmentedRow(
                        items = RemainderPolicy.entries,
                        selected = trim.remainderPolicy,
                        label = { it.label },
                        onSelect = { p -> viewModel.updateTrimConfig(recalculateClips = true) { it.copy(remainderPolicy = p) } },
                    )
                }

                TrimWorkflow.FIXED_TRIM -> {
                    StudioSectionHeader("Fixed Window Length", "${trim.fixedLengthSec}s")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(10, 15, 30, 45, 60).forEach { sec ->
                            StudioChoiceChip(
                                text = "${sec}s",
                                selected = trim.fixedLengthSec == sec,
                                onClick = { viewModel.updateTrimConfig(recalculateClips = true) { it.copy(fixedLengthSec = sec) } },
                            )
                        }
                    }
                    val maxStartMs = ((state.sourceMedia?.durationMs ?: 1000L) - trim.fixedLengthSec * 1000L).coerceAtLeast(100L)
                    StudioLabeledSlider(
                        label = "Slide Window Start",
                        valueText = formatTimecode(active?.startMs ?: 0L),
                        value = (active?.startMs ?: 0L).toFloat(),
                        valueRange = 0f..maxStartMs.toFloat(),
                        onValueChange = { v -> viewModel.slideFixedTrimWindow(v.toLong()) },
                    )
                }

                TrimWorkflow.MANUAL -> {
                    StudioSegmentedRow(
                        items = ManualMode.entries,
                        selected = trim.manualMode,
                        label = { it.label },
                        onSelect = { mm -> viewModel.updateTrimConfig(recalculateClips = true) { it.copy(manualMode = mm) } },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.splitAtPlayhead() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Icon(Icons.Filled.ContentCut, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Split Here")
                        }
                        if (trim.manualMode == ManualMode.REMOVE_MIDDLE) {
                            Button(
                                onClick = { viewModel.applyRemoveMiddleJumpCut() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text("Punch Out 2s")
                            }
                        }
                    }
                }
            }
        }

        // Smart Analysis Card
        StudioCard {
            StudioSectionHeader("Smart Cut Tools")
            StudioLabeledSlider(
                label = "Silence Threshold",
                valueText = "${(trim.silenceThreshold * 100).roundToInt()}%",
                value = trim.silenceThreshold,
                valueRange = 0.05f..0.40f,
                onValueChange = { v -> viewModel.updateTrimConfig(recalculateClips = false) { it.copy(silenceThreshold = v) } },
            )
            StudioLabeledSlider(
                label = "Min Silence Duration",
                valueText = "${trim.minSilenceMs} ms",
                value = trim.minSilenceMs.toFloat(),
                valueRange = 300f..2000f,
                onValueChange = { v -> viewModel.updateTrimConfig(recalculateClips = false) { it.copy(minSilenceMs = v.toLong()) } },
            )

            OutlinedButton(
                onClick = { viewModel.runSilenceJumpCut() },
                modifier = Modifier.fillMaxWidth().testTag("silence_jump_cut_button"),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Filled.GraphicEq, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Auto-Remove Silence (Jump Cut)")
            }

            OutlinedButton(
                onClick = { viewModel.runSceneChangeDetection() },
                enabled = !state.isAnalyzingSmartCut,
                modifier = Modifier.fillMaxWidth().testTag("detect_scenes_button"),
                shape = RoundedCornerShape(8.dp),
            ) {
                if (state.isAnalyzingSmartCut) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.MovieFilter, null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("Detect Scene Changes")
            }

            OutlinedButton(
                onClick = onOpenChaptersModal,
                modifier = Modifier.fillMaxWidth().testTag("chapters_modal_button"),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Filled.Topic, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Chapters / Timestamps")
            }

            state.smartCutMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = studio.timelineAccent,
                )
            }
        }

        // Active Clip Range Readout
        if (active != null) {
            StudioCard {
                StudioSectionHeader(
                    title = "Active Clip Range",
                    trailing = String.format("%.2fs", active.durationMs / 1000f),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        color = studio.surfaceWell,
                        border = BorderStroke(1.dp, studio.hairline),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text("Start (In)", style = MaterialTheme.typography.labelSmall, color = studio.mutedInk)
                            Text(formatTimecode(active.startMs), style = StudioMonoMedium)
                        }
                    }
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        color = studio.surfaceWell,
                        border = BorderStroke(1.dp, studio.hairline),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text("End (Out)", style = MaterialTheme.typography.labelSmall, color = studio.mutedInk)
                            Text(formatTimecode(active.endMs), style = StudioMonoMedium)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TuneToolPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    val visual = state.visualConfig
    val at = state.autoTune

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Smart Auto-Tune Card (3-Engine Architecture)
        StudioCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.AutoAwesome, null, tint = studio.timelineAccent, modifier = Modifier.size(18.dp))
                    Text("Smart Auto-Tune", style = MaterialTheme.typography.titleSmall)
                }
                StudioBadgePill(text = at.selectedEngine.badge, accent = at.selectedEngine == AutoTuneEngineType.LOCAL)
            }

            // 5 Style Goals
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AutoTuneGoal.entries.forEach { goal ->
                    StudioChoiceChip(
                        text = goal.label,
                        selected = at.goal == goal,
                        onClick = { viewModel.setAutoTuneGoal(goal) },
                        accentSelected = true,
                    )
                }
            }

            OutlinedTextField(
                value = at.customDirection,
                onValueChange = { viewModel.setAutoTuneDirection(it) },
                label = { Text("Custom direction (optional)") },
                placeholder = { Text("e.g. crisp bright voice, moody film") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // 3-Engine Selector
            StudioSectionHeader("Engine")
            StudioSegmentedRow(
                items = AutoTuneEngineType.entries,
                selected = at.selectedEngine,
                label = {
                    when (it) {
                        AutoTuneEngineType.LOCAL -> "Local"
                        AutoTuneEngineType.OPENROUTER_BYOK -> "OpenRouter"
                        AutoTuneEngineType.GEMINI_BYOK -> "Gemini"
                    }
                },
                onSelect = { viewModel.setAutoTuneEngine(it) },
            )

            if (at.selectedEngine == AutoTuneEngineType.OPENROUTER_BYOK) {
                ByokEngineConfigSection(
                    engineKey = "openrouter",
                    title = "OpenRouter API Key",
                    placeholder = "sk-or-v1-...",
                    hasSavedKey = at.hasOpenRouterKey,
                    presets = SmartAutoTuneEngine.openRouterPresetOptions,
                    selectedPreset = at.openRouterModelPreset,
                    customModel = at.openRouterCustomModel,
                    onSelectPreset = { viewModel.setOpenRouterModelPreset(it) },
                    onCustomModelChange = { viewModel.setOpenRouterCustomModel(it) },
                    onSaveKey = { viewModel.saveByokKey(AutoTuneEngineType.OPENROUTER_BYOK, it) },
                    onRevealSavedKey = { viewModel.getSavedByokKey(AutoTuneEngineType.OPENROUTER_BYOK) },
                )
            } else if (at.selectedEngine == AutoTuneEngineType.GEMINI_BYOK) {
                ByokEngineConfigSection(
                    engineKey = "gemini",
                    title = "Gemini API Key",
                    placeholder = "AIza...",
                    hasSavedKey = at.hasGeminiKey,
                    presets = SmartAutoTuneEngine.geminiPresetOptions,
                    selectedPreset = at.geminiModelPreset,
                    customModel = at.geminiCustomModel,
                    onSelectPreset = { viewModel.setGeminiModelPreset(it) },
                    onCustomModelChange = { viewModel.setGeminiCustomModel(it) },
                    onSaveKey = { viewModel.saveByokKey(AutoTuneEngineType.GEMINI_BYOK, it) },
                    onRevealSavedKey = { viewModel.getSavedByokKey(AutoTuneEngineType.GEMINI_BYOK) },
                )
            }

            Button(
                onClick = { viewModel.runSmartAutoTune() },
                enabled = !at.isRunning,
                modifier = Modifier.fillMaxWidth().testTag("run_autotune_button"),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (at.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Sampling 6 Keyframes & Audio…")
                } else {
                    Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Run Smart Auto-Tune")
                }
            }

            at.errorMessage?.let { err ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = studio.danger.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, studio.danger.copy(alpha = 0.5f)),
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = studio.danger,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            at.lastResult?.let { res ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = studio.surfaceWell,
                    border = BorderStroke(1.dp, studio.hairline),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(res.lookTitle, style = MaterialTheme.typography.titleSmall)
                            StudioBadgePill(text = res.modelUsed.take(22), accent = !res.fellBackToLocal)
                        }
                        if (res.fellBackToLocal && !res.fallbackReason.isNullOrBlank()) {
                            Text(
                                text = res.fallbackReason,
                                style = MaterialTheme.typography.labelSmall,
                                color = studio.warning,
                            )
                        }
                        Text(res.summary, style = MaterialTheme.typography.bodySmall, color = studio.mutedInk)

                        // Telemetry & Parameter Readout
                        Text(
                            text = "Luma ${(res.telemetry.meanLuma * 100).roundToInt()}% • Spread ${(res.telemetry.contrastSpread * 100).roundToInt()}% • Sharp ${(res.telemetry.edgeSharpness * 100).roundToInt()}% • Audio ${res.telemetry.audioRmsDb.roundToInt()} dB",
                            style = StudioMonoSmall,
                            color = studio.mutedInk,
                        )

                        // One-Tap Actionable Recommendations
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            StudioChoiceChip(
                                text = "Frame ${res.recommendedAspect.label}",
                                selected = state.visualConfig.aspectPreset == res.recommendedAspect,
                                onClick = { viewModel.applyAutoTuneRecommendedAspect(res.recommendedAspect) },
                            )
                            StudioChoiceChip(
                                text = "Split ${res.recommendedSplitSec}s",
                                selected = false,
                                onClick = { viewModel.applyAutoTuneRecommendedSplit(res.recommendedSplitSec) },
                            )
                            if (res.recommendSilenceCut) {
                                StudioChoiceChip(
                                    text = "Silence Jump-Cut",
                                    selected = false,
                                    onClick = { viewModel.runSilenceJumpCut() },
                                )
                            }
                            if (res.suggestedHighlights.isNotEmpty()) {
                                StudioChoiceChip(
                                    text = "Load ${res.suggestedHighlights.size} Highlights",
                                    selected = false,
                                    onClick = { viewModel.loadAutoTuneHighlights(res) },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Manual Color & Clarity Adjustments Card
        StudioCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Color & Clarity", style = MaterialTheme.typography.titleSmall)
                TextButton(
                    onClick = {
                        viewModel.updateVisual(recordUndo = true) {
                            it.copy(
                                brightness = 0f,
                                contrast = 1f,
                                saturation = 1f,
                                sharpen = 0f,
                                denoiseEnabled = false,
                                videoFadeInSec = 0f,
                                videoFadeOutSec = 0f,
                            )
                        }
                    },
                ) { Text("Reset") }
            }

            StudioLabeledSlider(
                label = "Brightness",
                valueText = String.format("%+.2f", visual.brightness),
                value = visual.brightness,
                valueRange = -0.35f..0.35f,
                onValueChange = { v -> viewModel.updateVisual { it.copy(brightness = v) } },
            )
            StudioLabeledSlider(
                label = "Contrast",
                valueText = String.format("%.2fx", visual.contrast),
                value = visual.contrast,
                valueRange = 0.6f..1.5f,
                onValueChange = { v -> viewModel.updateVisual { it.copy(contrast = v) } },
            )
            StudioLabeledSlider(
                label = "Saturation",
                valueText = String.format("%.2fx", visual.saturation),
                value = visual.saturation,
                valueRange = 0f..1.6f,
                onValueChange = { v -> viewModel.updateVisual { it.copy(saturation = v) } },
            )
            StudioLabeledSlider(
                label = "Sharpen",
                valueText = "${(visual.sharpen * 100).roundToInt()}%",
                value = visual.sharpen,
                valueRange = 0f..1f,
                onValueChange = { v -> viewModel.updateVisual { it.copy(sharpen = v) } },
            )
            StudioToggleRow(
                title = "Video Denoise",
                subtitle = "Smooth low-light shadow grain on export",
                checked = visual.denoiseEnabled,
                onCheckedChange = { c -> viewModel.updateVisual(recordUndo = true) { it.copy(denoiseEnabled = c) } },
            )
            StudioLabeledSlider(
                label = "Video Fade-In",
                valueText = String.format("%.1fs", visual.videoFadeInSec),
                value = visual.videoFadeInSec,
                valueRange = 0f..3f,
                onValueChange = { v -> viewModel.updateVisual { it.copy(videoFadeInSec = v) } },
            )
            StudioLabeledSlider(
                label = "Video Fade-Out",
                valueText = String.format("%.1fs", visual.videoFadeOutSec),
                value = visual.videoFadeOutSec,
                valueRange = 0f..3f,
                onValueChange = { v -> viewModel.updateVisual { it.copy(videoFadeOutSec = v) } },
            )
        }
    }
}

@Composable
private fun ByokEngineConfigSection(
    engineKey: String,
    title: String,
    placeholder: String,
    hasSavedKey: Boolean,
    presets: List<ModelPresetOption>,
    selectedPreset: String,
    customModel: String,
    onSelectPreset: (String) -> Unit,
    onCustomModelChange: (String) -> Unit,
    onSaveKey: (String) -> Unit,
    onRevealSavedKey: () -> String,
) {
    val studio = LocalStudioColors.current
    var keyDraft by remember(engineKey) { mutableStateOf("") }
    var showKey by remember(engineKey) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = studio.surfaceWell,
        border = BorderStroke(1.dp, studio.hairline),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.labelMedium)
                if (hasSavedKey) {
                    StudioBadgePill(text = "Saved ✓", accent = true)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    label = { Text(if (hasSavedKey) "Update API key" else "Paste API key") },
                    placeholder = { Text(placeholder) },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val nextShow = !showKey
                                showKey = nextShow
                                if (nextShow && keyDraft.isEmpty() && hasSavedKey) {
                                    keyDraft = onRevealSavedKey()
                                }
                            },
                        ) {
                            Icon(
                                imageVector = if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showKey) "Hide API key" else "Show API key",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        onSaveKey(keyDraft)
                        keyDraft = ""
                        showKey = false
                    },
                    shape = RoundedCornerShape(8.dp),
                ) { Text("Save") }
                if (hasSavedKey) {
                    IconButton(
                        onClick = {
                            onSaveKey("")
                            keyDraft = ""
                            showKey = false
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = "Clear saved API key",
                            tint = studio.danger,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Text("Model Preset", style = MaterialTheme.typography.labelSmall, color = studio.mutedInk)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                presets.forEach { preset ->
                    StudioChoiceChip(
                        text = preset.shortLabel,
                        selected = selectedPreset == preset.id && customModel.isBlank(),
                        onClick = {
                            onCustomModelChange("")
                            onSelectPreset(preset.id)
                        },
                    )
                }
            }

            OutlinedTextField(
                value = customModel,
                onValueChange = onCustomModelChange,
                label = { Text("Or custom model ID") },
                placeholder = {
                    Text(if (engineKey == "openrouter") "e.g. google/gemini-2.5-flash" else "e.g. gemini-2.5-flash")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "Tries best model first, auto-falls back if busy. Stored encrypted on-device.",
                style = MaterialTheme.typography.labelSmall,
                color = studio.mutedInk,
            )
        }
    }
}
