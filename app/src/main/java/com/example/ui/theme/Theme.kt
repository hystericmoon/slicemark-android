package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class StudioExtendedColors(
    val isDark: Boolean,
    val timelineAccent: Color,
    val timelineAccentDim: Color,
    val surfaceWell: Color,
    val surfaceElevated: Color,
    val hairline: Color,
    val mutedInk: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
)

val LocalStudioColors = staticCompositionLocalOf {
    StudioExtendedColors(
        isDark = true,
        timelineAccent = DarkTimelineBlue,
        timelineAccentDim = DarkTimelineBlueDim,
        surfaceWell = DarkSurfaceWell,
        surfaceElevated = DarkSurfaceElevated,
        hairline = DarkHairline,
        mutedInk = DarkMutedInk,
        success = StudioSuccess,
        warning = StudioWarning,
        danger = StudioDanger,
    )
}

private val SlicemarkDarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkSurfaceElevated,
    onPrimaryContainer = DarkInk,
    secondary = DarkTimelineBlue,
    onSecondary = Color.White,
    secondaryContainer = DarkTimelineBlueDim,
    onSecondaryContainer = DarkInk,
    tertiary = StudioSuccess,
    onTertiary = DarkOnPrimary,
    background = DarkCanvas,
    onBackground = DarkInk,
    surface = DarkSurface,
    onSurface = DarkInk,
    surfaceVariant = DarkSurfaceWell,
    onSurfaceVariant = DarkMutedInk,
    outline = DarkHairline,
    outlineVariant = DarkSurfaceElevated,
    error = StudioDanger,
    onError = DarkOnPrimary,
)

private val SlicemarkLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightSurfaceElevated,
    onPrimaryContainer = LightInk,
    secondary = LightTimelineBlue,
    onSecondary = Color.White,
    secondaryContainer = LightTimelineBlueDim,
    onSecondaryContainer = LightInk,
    tertiary = StudioSuccess,
    onTertiary = Color.White,
    background = LightCanvas,
    onBackground = LightInk,
    surface = LightSurface,
    onSurface = LightInk,
    surfaceVariant = LightSurfaceWell,
    onSurfaceVariant = LightMutedInk,
    outline = LightHairline,
    outlineVariant = LightSurfaceElevated,
    error = Color(0xFFDC2626),
    onError = Color.White,
)

val SlicemarkShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun SlicemarkTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) SlicemarkDarkColorScheme else SlicemarkLightColorScheme
    val extended = if (darkTheme) {
        StudioExtendedColors(
            isDark = true,
            timelineAccent = DarkTimelineBlue,
            timelineAccentDim = DarkTimelineBlueDim,
            surfaceWell = DarkSurfaceWell,
            surfaceElevated = DarkSurfaceElevated,
            hairline = DarkHairline,
            mutedInk = DarkMutedInk,
            success = StudioSuccess,
            warning = StudioWarning,
            danger = StudioDanger,
        )
    } else {
        StudioExtendedColors(
            isDark = false,
            timelineAccent = LightTimelineBlue,
            timelineAccentDim = LightTimelineBlueDim,
            surfaceWell = LightSurfaceWell,
            surfaceElevated = LightSurfaceElevated,
            hairline = LightHairline,
            mutedInk = LightMutedInk,
            success = Color(0xFF059669),
            warning = Color(0xFFD97706),
            danger = Color(0xFFDC2626),
        )
    }

    CompositionLocalProvider(LocalStudioColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SlicemarkTypography,
            shapes = SlicemarkShapes,
            content = content,
        )
    }
}
