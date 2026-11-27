package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.ClipSegment
import com.example.model.ExportProgressState
import com.example.model.HistoryEntry
import com.example.model.HistoryEntryType
import com.example.model.SubtitleAndChapterParser
import com.example.ui.theme.LocalStudioColors
import com.example.ui.theme.StudioMonoSmall
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalHistorySheet(
    history: List<HistoryEntry>,
    hasLoadedVideo: Boolean,
    onApplyLook: (HistoryEntry) -> Unit,
    onDeleteItem: (String) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val studio = LocalStudioColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dateFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.History, null, tint = studio.timelineAccent)
                    Column {
                        Text("Local History", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${history.size}/20 saved on-device • zero cloud sync",
                            style = MaterialTheme.typography.bodySmall,
                            color = studio.mutedInk,
                        )
                    }
                }
                if (history.isNotEmpty()) {
                    TextButton(onClick = onClearAll, modifier = Modifier.testTag("clear_history_button")) {
                        Text("Clear All", color = studio.danger)
                    }
                }
            }

            if (history.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = studio.surfaceWell,
                    border = BorderStroke(1.dp, studio.hairline),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Filled.History, null, tint = studio.mutedInk, modifier = Modifier.size(32.dp))
                        Text("No saved looks or exports yet", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Run Smart Auto-Tune or export a clip to save reusable looks locally.",
                            style = MaterialTheme.typography.bodySmall,
                            color = studio.mutedInk,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(history, key = { it.id }) { entry ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = studio.surfaceWell,
                            border = BorderStroke(1.dp, studio.hairline),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            imageVector = if (entry.type == HistoryEntryType.AUTO_TUNE_LOOK) {
                                                Icons.Filled.AutoAwesome
                                            } else {
                                                Icons.Filled.Download
                                            },
                                            contentDescription = null,
                                            tint = if (entry.type == HistoryEntryType.AUTO_TUNE_LOOK) studio.timelineAccent else studio.success,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = entry.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text(
                                        text = dateFmt.format(Date(entry.timestampMs)),
                                        style = StudioMonoSmall,
                                        color = studio.mutedInk,
                                    )
                                }

                                Text(
                                    text = "${entry.sourceVideoName} • ${entry.subtitle}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = studio.mutedInk,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Button(
                                        onClick = {
                                            onApplyLook(entry)
                                            onDismiss()
                                        },
                                        enabled = hasLoadedVideo,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.height(34.dp),
                                    ) {
                                        Text(
                                            text = if (hasLoadedVideo) "Apply Look" else "Load Video to Apply",
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                    IconButton(onClick = { onDeleteItem(entry.id) }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete history item", tint = studio.mutedInk)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class ChaptersModalTab(val label: String) {
    IMPORT("Paste to Split"),
    EXPORT("Export Chapters"),
}

@Composable
fun ChaptersTimestampDialog(
    clips: List<ClipSegment>,
    totalDurationMs: Long,
    onApplyChapters: (String) -> Unit,
    onExportJson: () -> Unit,
    onDismiss: () -> Unit,
) {
    val studio = LocalStudioColors.current
    val clipboard = LocalClipboardManager.current
    var activeTab by remember { mutableStateOf(ChaptersModalTab.IMPORT) }
    var text by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }

    val sampleChapters = remember {
        "00:00 Opening Hook\n00:15 Product Walkthrough\n00:30 Workflow Detail\n00:45 Export Summary"
    }
    val parsedPreview = remember(text, totalDurationMs) {
        SubtitleAndChapterParser.parseChapters(text, totalDurationMs.coerceAtLeast(1L))
    }
    val exportedText = remember(clips) {
        SubtitleAndChapterParser.exportChaptersText(clips)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chapters & Timestamps") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StudioSegmentedRow(
                    items = ChaptersModalTab.entries,
                    selected = activeTab,
                    label = { it.label },
                    onSelect = { activeTab = it },
                )

                if (activeTab == ChaptersModalTab.IMPORT) {
                    Text(
                        text = "Paste YouTube-style timestamps (e.g. 00:15 Demo) to split clips by chapter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = studio.mutedInk,
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        minLines = 5,
                        maxLines = 8,
                        placeholder = { Text(sampleChapters) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { text = sampleChapters }) {
                            Text("Insert example")
                        }
                        Text(
                            text = "${parsedPreview.size} ${if (parsedPreview.size == 1) "clip" else "clips"} detected",
                            style = StudioMonoSmall,
                            color = studio.mutedInk,
                        )
                    }
                } else {
                    Text(
                        text = "Copy timeline cuts as YouTube chapter markers or save slicemark_chapters.json.",
                        style = MaterialTheme.typography.bodySmall,
                        color = studio.mutedInk,
                    )
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = studio.surfaceWell,
                        border = BorderStroke(1.dp, studio.hairline),
                    ) {
                        Text(
                            text = exportedText.ifBlank { "No clips in queue." },
                            style = StudioMonoSmall,
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onExportJson() },
                            enabled = clips.isNotEmpty(),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).testTag("export_chapters_json_button"),
                        ) {
                            Icon(Icons.Filled.Download, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Download JSON")
                        }
                        Button(
                            onClick = {
                                clipboard.setText(AnnotatedString(exportedText))
                                copied = true
                            },
                            enabled = exportedText.isNotBlank(),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(if (copied) "Copied" else "Copy")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (activeTab == ChaptersModalTab.IMPORT) {
                Button(
                    enabled = parsedPreview.isNotEmpty(),
                    onClick = {
                        onApplyChapters(text)
                        onDismiss()
                    },
                ) { Text("Create ${if (parsedPreview.isNotEmpty()) parsedPreview.size else ""} Clips".trim()) }
            } else {
                Button(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            if (activeTab == ChaptersModalTab.IMPORT) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun ExportProgressBanner(
    progress: ExportProgressState,
    onCancel: () -> Unit,
    onShare: (List<android.net.Uri>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    if (!progress.isExporting && progress.lastCompletedSummary == null && progress.errorMessage == null) {
        return
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            when {
                progress.errorMessage != null -> studio.danger
                progress.lastCompletedSummary != null -> studio.success
                else -> studio.timelineAccent
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                progress.isExporting -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(progress.statusMessage, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = onCancel, modifier = Modifier.height(32.dp)) {
                            Text("Cancel", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    LinearProgressIndicator(
                        progress = { progress.overallProgress.coerceIn(0f, 1f) },
                        color = studio.timelineAccent,
                        trackColor = studio.surfaceWell,
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                }

                progress.lastCompletedSummary != null -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.CheckCircle, null, tint = studio.success, modifier = Modifier.size(18.dp))
                            Text(progress.lastCompletedSummary, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                    if (progress.lastCompletedUris.isNotEmpty()) {
                        Button(
                            onClick = { onShare(progress.lastCompletedUris) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(36.dp).testTag("share_export_button"),
                        ) {
                            Icon(Icons.Filled.Share, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share Exported File${if (progress.lastCompletedUris.size > 1) "s" else ""}")
                        }
                    }
                }

                progress.errorMessage != null -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(progress.errorMessage, style = MaterialTheme.typography.bodySmall, color = studio.danger, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}
