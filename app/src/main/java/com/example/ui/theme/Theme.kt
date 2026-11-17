package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

private val DirectTunnelColorScheme =
  darkColorScheme(
    primary = EmeraldPulse,
    onPrimary = ObsidianBg,
    primaryContainer = EmeraldDim,
    onPrimaryContainer = EmeraldPulse,
    secondary = CyanStream,
    onSecondary = ObsidianBg,
    secondaryContainer = CyanDim,
    onSecondaryContainer = CyanStream,
    tertiary = AmberWarn,
    onTertiary = ObsidianBg,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = ObsidianSurface,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianCard,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianCard,
    surfaceContainerHigh = ObsidianCardElevated,
    outline = ObsidianOutline,
    outlineVariant = ObsidianOutline.copy(alpha = 0.5f),
    error = CoralError,
    onError = ObsidianBg,
  )

val DirectTunnelShapes =
  Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
  )

@Composable
fun DirectTunnelTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = DirectTunnelColorScheme,
    typography = AppTypography,
    shapes = DirectTunnelShapes,
    content = content,
  )
}
