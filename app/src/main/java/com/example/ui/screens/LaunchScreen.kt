package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.EditorUiState
import com.example.ui.components.SlicemarkBrandMark
import com.example.ui.components.StudioBadgePill
import com.example.ui.theme.LocalStudioColors

@Composable
fun LaunchScreen(
    state: EditorUiState,
    onSelectVideoPhotoPicker: () -> Unit,
    onSelectVideoSaf: () -> Unit,
    onTrySampleClip: () -> Unit,
    onOpenHistory: () -> Unit,
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    val bgColor = MaterialTheme.colorScheme.background
    val glowColor = studio.timelineAccent.copy(alpha = if (studio.isDark) 0.12f else 0.08f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
            .drawBehind {
                // Subtle architectural studio radial wash
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(glowColor, bgColor),
                        center = Offset(size.width * 0.5f, size.height * 0.22f),
                        radius = size.maxDimension * 0.65f,
                    )
                )
            }
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top Studio Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SlicemarkBrandMark(sizeDp = 28)
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.brand_studio),
                            style = MaterialTheme.typography.labelSmall,
                            color = studio.mutedInk,
                        )
                    }
                }

                IconButton(
                    onClick = onToggleTheme,
                    modifier = Modifier.testTag("launch_theme_toggle"),
                ) {
                    Icon(
                        imageVector = if (state.darkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                        contentDescription = if (state.darkTheme) "Switch to Light Mode" else "Switch to Dark Mode",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }

            Spacer(Modifier.height(48.dp))

            // Centered Hero Intake Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                StudioBadgePill(text = "ON-DEVICE VIDEO MASTERING", accent = true)

                Text(
                    text = stringResource(R.string.home_headline),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                Text(
                    text = stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = studio.mutedInk,
                )

                Spacer(Modifier.height(6.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, studio.hairline),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = onSelectVideoPhotoPicker,
                            enabled = !state.isLoadingMedia,
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 54.dp)
                                .testTag("select_video_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            if (state.isLoadingMedia) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(state.loadingStatus.ifBlank { "Loading…" })
                            } else {
                                Icon(Icons.Filled.VideoLibrary, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(R.string.action_select_video))
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = onTrySampleClip,
                                enabled = !state.isLoadingMedia,
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("try_sample_button"),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Icon(Icons.Filled.PlayCircleOutline, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Sample Clip", maxLines = 1)
                            }

                            OutlinedButton(
                                onClick = onOpenHistory,
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("launch_history_button"),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Icon(Icons.Filled.History, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("History (${state.history.size})", maxLines = 1)
                            }
                        }

                        TextButton(
                            onClick = onSelectVideoSaf,
                            enabled = !state.isLoadingMedia,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) {
                            Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(16.dp), tint = studio.mutedInk)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Browse Files via System Picker",
                                style = MaterialTheme.typography.labelMedium,
                                color = studio.mutedInk,
                            )
                        }
                    }
                }

                // Studio Capabilities Footer Strip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StudioBadgePill("Lossless MP4 Split")
                    StudioBadgePill("3-Engine Auto-Tune")
                    StudioBadgePill("SRT & Voice EQ")
                }
            }
        }
    }
}
