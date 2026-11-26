package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import com.example.model.ClipSegment
import com.example.model.EditorToolTab
import com.example.model.EditorUiState
import com.example.model.ExportTargetMode
import com.example.ui.components.AudioAndMotionToolPanel
import com.example.ui.components.ClipQueuePanel
import com.example.ui.components.ExportProgressBanner
import com.example.ui.components.ExportToolPanel
import com.example.ui.components.FrameToolPanel
import com.example.ui.components.InteractiveTimelinePanel
import com.example.ui.components.SlicemarkBrandMark
import com.example.ui.components.StudioSegmentedRow
import com.example.ui.components.TextAndCaptionsToolPanel
import com.example.ui.components.TransportControlStrip
import com.example.ui.components.TrimToolPanel
import com.example.ui.components.TuneToolPanel
import com.example.ui.components.VideoPreviewViewport
import com.example.ui.theme.LocalStudioColors
import com.example.ui.theme.StudioMonoSmall
import com.example.viewmodel.EditorViewModel

@Composable
fun EditorWorkspaceScreen(
    state: EditorUiState,
    player: ExoPlayer,
    viewModel: EditorViewModel,
    onReplaceVideo: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenChaptersModal: () -> Unit,
    onPickLogo: () -> Unit,
    onPickSubtitleFile: () -> Unit,
    onPickBgAudio: () -> Unit,
    onTriggerExport: (List<ClipSegment>?) -> Unit,
    onShareExportUris: (List<android.net.Uri>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // Top Studio Header Bar
        StudioTopBar(
            state = state,
            onReplaceVideo = onReplaceVideo,
            onOpenHistory = onOpenHistory,
            onToggleTheme = { viewModel.toggleDarkTheme() },
            onPrimaryExport = { onTriggerExport(null) },
        )

        // Live Export Progress / Completion / Error Banner
        ExportProgressBanner(
            progress = state.exportProgress,
            onCancel = { viewModel.cancelExport() },
            onShare = onShareExportUris,
            onDismiss = { viewModel.dismissExportOutcome() },
        )

        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val isExpandedWide = maxWidth >= 840.dp

            if (isExpandedWide) {
                // Tablet / Landscape 3-Pane Studio Layout (matches attached web layout)
                ExpandedThreePaneWorkspace(
                    state = state,
                    player = player,
                    viewModel = viewModel,
                    onOpenChaptersModal = onOpenChaptersModal,
                    onPickLogo = onPickLogo,
                    onPickSubtitleFile = onPickSubtitleFile,
                    onPickBgAudio = onPickBgAudio,
                    onTriggerExport = onTriggerExport,
                )
            } else {
                // Portrait Phone Layout: Live Preview + Timeline + Half-Height Docked Tool Sheet
                PortraitStudioWorkspace(
                    state = state,
                    player = player,
                    viewModel = viewModel,
                    onOpenChaptersModal = onOpenChaptersModal,
                    onPickLogo = onPickLogo,
                    onPickSubtitleFile = onPickSubtitleFile,
                    onPickBgAudio = onPickBgAudio,
                    onTriggerExport = onTriggerExport,
                )
            }
        }
    }
}

@Composable
private fun StudioTopBar(
    state: EditorUiState,
    onReplaceVideo: () -> Unit,
    onOpenHistory: () -> Unit,
    onToggleTheme: () -> Unit,
    onPrimaryExport: () -> Unit,
) {
    val studio = LocalStudioColors.current
    val selectedCount = state.selectedClips.size

    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, studio.hairline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SlicemarkBrandMark(sizeDp = 24)
                Column {
                    Text("Slicemark", style = MaterialTheme.typography.titleMedium)
                    Text("by Haho Studio", style = MaterialTheme.typography.labelSmall, color = studio.mutedInk)
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onReplaceVideo, modifier = Modifier.testTag("replace_video_button")) {
                    Icon(Icons.Filled.SwapHoriz, contentDescription = "Replace Video", tint = MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = onOpenHistory, modifier = Modifier.testTag("topbar_history_button")) {
                    Icon(Icons.Filled.History, contentDescription = "Local History", tint = MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = onToggleTheme, modifier = Modifier.testTag("topbar_theme_toggle")) {
                    Icon(
                        imageVector = if (state.darkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                        contentDescription = "Toggle Theme",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Button(
                    onClick = onPrimaryExport,
                    enabled = !state.exportProgress.isExporting && selectedCount > 0,
                    shape = RoundedCornerShape(9.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.defaultMinSize(minHeight = 38.dp).testTag("topbar_export_button"),
                ) {
                    Icon(Icons.Filled.Download, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (selectedCount > 1) "Export $selectedCount" else "Export",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun PortraitStudioWorkspace(
    state: EditorUiState,
    player: ExoPlayer,
    viewModel: EditorViewModel,
    onOpenChaptersModal: () -> Unit,
    onPickLogo: () -> Unit,
    onPickSubtitleFile: () -> Unit,
    onPickBgAudio: () -> Unit,
    onTriggerExport: (List<ClipSegment>?) -> Unit,
) {
    val studio = LocalStudioColors.current

    Column(modifier = Modifier.fillMaxSize()) {
        // Upper Live Workspace (Preview always stays visible above half-height sheet!)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxSize()) {
                VideoPreviewViewport(
                    state = state,
                    player = player,
                    onToggleCompareOriginal = { viewModel.toggleCompareOriginal() },
                    onUpdateCrop = { crop -> viewModel.updateVisual { it.copy(cropRect = crop) } },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )

                TransportControlStrip(
                    state = state,
                    onSplit = { viewModel.splitAtPlayhead() },
                    onMarkIn = { viewModel.markInAtPlayhead() },
                    onMarkOut = { viewModel.markOutAtPlayhead() },
                    onSnapshot = { viewModel.saveFrameSnapshot() },
                    onUndo = { viewModel.undo() },
                    onRedo = { viewModel.redo() },
                    onJumpStart = { viewModel.jumpToClipStart() },
                    onStepBack = { viewModel.stepFrame(-1) },
                    onPlayPause = { viewModel.togglePlayPause() },
                    onStepForward = { viewModel.stepFrame(1) },
                    onJumpEnd = { viewModel.jumpToClipEnd() },
                    onToggleLoop = { viewModel.updateAudioMotion { it.copy(loopActiveClip = !it.loopActiveClip) } },
                    onToggleMute = { viewModel.updateAudioMotion { it.copy(muted = !it.muted) } },
                    onCycleSpeed = {
                        val speeds = listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)
                        val cur = state.audioMotionConfig.playbackSpeed
                        val next = speeds.firstOrNull { it > cur + 0.05f } ?: speeds.first()
                        viewModel.updateAudioMotion { it.copy(playbackSpeed = next) }
                    },
                )

                InteractiveTimelinePanel(
                    state = state,
                    onScrubTo = { viewModel.scrubTimelineTo(it) },
                    onSelectClip = { viewModel.selectClip(it, seekAndPreview = true) },
                    onUpdateActiveClipRange = { s, e, undo -> viewModel.updateActiveClipRange(s, e, undo) },
                    onSetZoom = { viewModel.setTimelineZoom(it) },
                    onToggleSnap = { viewModel.toggleSnap() },
                )
            }

            // Confirmed Phase 1 UX: Half-height docked sheet keeping live video preview visible above
            androidx.compose.animation.AnimatedVisibility(
                visible = state.portraitSheetOpen,
                enter = slideInVertically(animationSpec = spring()) { it },
                exit = slideOutVertically(animationSpec = spring()) { it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.56f),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                    color = MaterialTheme.colorScheme.background,
                    border = BorderStroke(1.dp, studio.hairline),
                    tonalElevation = 4.dp,
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Sheet Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = state.activeToolTab.label,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            IconButton(
                                onClick = { viewModel.closePortraitSheet() },
                                modifier = Modifier.size(34.dp).testTag("close_tool_sheet_button"),
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Close tool panel", modifier = Modifier.size(18.dp))
                            }
                        }

                        // Scrollable Tool Body
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                        ) {
                            ActiveToolContent(
                                tab = state.activeToolTab,
                                state = state,
                                viewModel = viewModel,
                                onOpenChaptersModal = onOpenChaptersModal,
                                onPickLogo = onPickLogo,
                                onPickSubtitleFile = onPickSubtitleFile,
                                onPickBgAudio = onPickBgAudio,
                                onTriggerExport = onTriggerExport,
                            )
                        }
                    }
                }
            }
        }

        // Bottom 7-Tool Studio Dock
        BottomToolDock(
            activeTab = state.activeToolTab,
            isSheetOpen = state.portraitSheetOpen,
            clipCount = state.clips.size,
            onSelectTab = { tab ->
                if (state.portraitSheetOpen && state.activeToolTab == tab) {
                    viewModel.closePortraitSheet()
                } else {
                    viewModel.selectToolTab(tab, openSheet = true)
                }
            },
        )
    }
}

@Composable
private fun ExpandedThreePaneWorkspace(
    state: EditorUiState,
    player: ExoPlayer,
    viewModel: EditorViewModel,
    onOpenChaptersModal: () -> Unit,
    onPickLogo: () -> Unit,
    onPickSubtitleFile: () -> Unit,
    onPickBgAudio: () -> Unit,
    onTriggerExport: (List<ClipSegment>?) -> Unit,
) {
    val studio = LocalStudioColors.current
    val leftTabs = listOf(
        EditorToolTab.TRIM,
        EditorToolTab.TUNE,
        EditorToolTab.FRAME,
        EditorToolTab.TEXT,
        EditorToolTab.AUDIO,
        EditorToolTab.EXPORT,
    )
    val selectedLeftTab = if (state.activeToolTab == EditorToolTab.CLIPS) EditorToolTab.TRIM else state.activeToolTab

    Row(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        // Left Pane: 6-Tool Inspector
        Column(
            modifier = Modifier
                .width(330.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, studio.hairline),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                leftTabs.forEach { tab ->
                    val active = selectedLeftTab == tab
                    Surface(
                        onClick = { viewModel.selectToolTab(tab, openSheet = false) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (active) studio.surfaceElevated else Color.Transparent,
                        border = BorderStroke(1.dp, if (active) studio.hairline else Color.Transparent),
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 46.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Icon(
                                imageVector = toolIcon(tab),
                                contentDescription = tab.label,
                                tint = if (active) MaterialTheme.colorScheme.onSurface else studio.mutedInk,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) MaterialTheme.colorScheme.onSurface else studio.mutedInk,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                ActiveToolContent(
                    tab = selectedLeftTab,
                    state = state,
                    viewModel = viewModel,
                    onOpenChaptersModal = onOpenChaptersModal,
                    onPickLogo = onPickLogo,
                    onPickSubtitleFile = onPickSubtitleFile,
                    onPickBgAudio = onPickBgAudio,
                    onTriggerExport = onTriggerExport,
                )
            }
        }

        // Center Pane: Preview + Transport + Timeline
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            VideoPreviewViewport(
                state = state,
                player = player,
                onToggleCompareOriginal = { viewModel.toggleCompareOriginal() },
                onUpdateCrop = { crop -> viewModel.updateVisual { it.copy(cropRect = crop) } },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            TransportControlStrip(
                state = state,
                onSplit = { viewModel.splitAtPlayhead() },
                onMarkIn = { viewModel.markInAtPlayhead() },
                onMarkOut = { viewModel.markOutAtPlayhead() },
                onSnapshot = { viewModel.saveFrameSnapshot() },
                onUndo = { viewModel.undo() },
                onRedo = { viewModel.redo() },
                onJumpStart = { viewModel.jumpToClipStart() },
                onStepBack = { viewModel.stepFrame(-1) },
                onPlayPause = { viewModel.togglePlayPause() },
                onStepForward = { viewModel.stepFrame(1) },
                onJumpEnd = { viewModel.jumpToClipEnd() },
                onToggleLoop = { viewModel.updateAudioMotion { it.copy(loopActiveClip = !it.loopActiveClip) } },
                onToggleMute = { viewModel.updateAudioMotion { it.copy(muted = !it.muted) } },
                onCycleSpeed = {
                    val speeds = listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)
                    val cur = state.audioMotionConfig.playbackSpeed
                    val next = speeds.firstOrNull { it > cur + 0.05f } ?: speeds.first()
                    viewModel.updateAudioMotion { it.copy(playbackSpeed = next) }
                },
            )
            InteractiveTimelinePanel(
                state = state,
                onScrubTo = { viewModel.scrubTimelineTo(it) },
                onSelectClip = { viewModel.selectClip(it, seekAndPreview = true) },
                onUpdateActiveClipRange = { s, e, undo -> viewModel.updateActiveClipRange(s, e, undo) },
                onSetZoom = { viewModel.setTimelineZoom(it) },
                onToggleSnap = { viewModel.toggleSnap() },
            )
        }

        // Right Pane: Clip Queue + Separate/Merge + Quick Export
        Column(
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, studio.hairline),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                ClipQueuePanel(
                    state = state,
                    viewModel = viewModel,
                    onExportSingleClip = { clip -> onTriggerExport(listOf(clip)) },
                )
            }

            // Bottom Export Bar matching reference layout
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, studio.hairline)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StudioSegmentedRow(
                    items = ExportTargetMode.entries,
                    selected = state.exportConfig.targetMode,
                    label = { it.label },
                    onSelect = { tm -> viewModel.updateExportConfig { it.copy(targetMode = tm) } },
                )
                Text(
                    text = "${state.exportConfig.format.label} • ${if (state.isLosslessEligible) "Lossless" else state.exportConfig.qualityPreset.label}",
                    style = StudioMonoSmall,
                    color = studio.mutedInk,
                )
                Button(
                    onClick = { onTriggerExport(null) },
                    enabled = !state.exportProgress.isExporting && state.selectedClips.isNotEmpty(),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 46.dp),
                ) {
                    Icon(Icons.Filled.Download, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export ${state.selectedClips.size} ${state.exportConfig.format.label}s")
                }
            }
        }
    }
}

@Composable
private fun ActiveToolContent(
    tab: EditorToolTab,
    state: EditorUiState,
    viewModel: EditorViewModel,
    onOpenChaptersModal: () -> Unit,
    onPickLogo: () -> Unit,
    onPickSubtitleFile: () -> Unit,
    onPickBgAudio: () -> Unit,
    onTriggerExport: (List<ClipSegment>?) -> Unit,
) {
    when (tab) {
        EditorToolTab.TRIM -> TrimToolPanel(state, viewModel, onOpenChaptersModal)
        EditorToolTab.TUNE -> TuneToolPanel(state, viewModel)
        EditorToolTab.FRAME -> FrameToolPanel(state, viewModel)
        EditorToolTab.TEXT -> TextAndCaptionsToolPanel(state, viewModel, onPickLogo, onPickSubtitleFile)
        EditorToolTab.AUDIO -> AudioAndMotionToolPanel(state, viewModel, onPickBgAudio)
        EditorToolTab.EXPORT -> ExportToolPanel(state, viewModel, { onTriggerExport(null) })
        EditorToolTab.CLIPS -> ClipQueuePanel(state, viewModel, { clip -> onTriggerExport(listOf(clip)) })
    }
}

@Composable
private fun BottomToolDock(
    activeTab: EditorToolTab,
    isSheetOpen: Boolean,
    clipCount: Int,
    onSelectTab: (EditorToolTab) -> Unit,
) {
    val studio = LocalStudioColors.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, studio.hairline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            EditorToolTab.entries.forEach { tab ->
                val selected = isSheetOpen && activeTab == tab
                val tint = if (selected) studio.timelineAccent else studio.mutedInk
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) studio.timelineAccentDim else Color.Transparent)
                        .clickable { onSelectTab(tab) }
                        .padding(vertical = 6.dp)
                        .testTag("tool_tab_${tab.name.lowercase()}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(toolIcon(tab), contentDescription = tab.label, tint = tint, modifier = Modifier.size(18.dp))
                    Text(
                        text = if (tab == EditorToolTab.CLIPS) "Clips($clipCount)" else tab.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = if (selected) MaterialTheme.colorScheme.onSurface else tint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun toolIcon(tab: EditorToolTab): ImageVector = when (tab) {
    EditorToolTab.TRIM -> Icons.Filled.ContentCut
    EditorToolTab.TUNE -> Icons.Filled.AutoFixHigh
    EditorToolTab.FRAME -> Icons.Filled.AspectRatio
    EditorToolTab.TEXT -> Icons.Filled.TextFields
    EditorToolTab.AUDIO -> Icons.Filled.GraphicEq
    EditorToolTab.EXPORT -> Icons.Filled.Tune
    EditorToolTab.CLIPS -> Icons.Filled.Layers
}
