package com.autotube.ai.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DarkScheme = darkColorScheme(
    primary = AutoTubeColors.Red,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5C0F1C),
    onPrimaryContainer = Color(0xFFFFD9DE),
    inversePrimary = AutoTubeColors.RedDeep,
    secondary = AutoTubeColors.Amber,
    onSecondary = Color(0xFF231B00),
    secondaryContainer = Color(0xFF4A3B00),
    onSecondaryContainer = Color(0xFFFFE9A8),
    tertiary = AutoTubeColors.Teal,
    onTertiary = Color(0xFF00201B),
    tertiaryContainer = Color(0xFF0B4A41),
    onTertiaryContainer = Color(0xFFB4F2E6),
    background = AutoTubeColors.Background,
    onBackground = AutoTubeColors.TextPrimary,
    surface = AutoTubeColors.Surface,
    onSurface = AutoTubeColors.TextPrimary,
    surfaceVariant = AutoTubeColors.SurfaceVariant,
    onSurfaceVariant = AutoTubeColors.TextSecondary,
    surfaceTint = AutoTubeColors.Red,
    inverseSurface = AutoTubeColors.TextPrimary,
    inverseOnSurface = AutoTubeColors.Background,
    error = AutoTubeColors.Error,
    onError = Color(0xFF2B0000),
    errorContainer = Color(0xFF5A1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = AutoTubeColors.Outline,
    outlineVariant = AutoTubeColors.OutlineVariant,
    scrim = Color.Black,
    surfaceBright = Color(0xFF2C2E37),
    surfaceContainer = AutoTubeColors.Surface,
    surfaceContainerHigh = AutoTubeColors.SurfaceHigh,
    surfaceContainerHighest = AutoTubeColors.SurfaceHighest,
    surfaceContainerLow = AutoTubeColors.SurfaceLow,
    surfaceContainerLowest = Color(0xFF0A0B0E),
    surfaceDim = AutoTubeColors.Background,
)

private val Base = Typography()

private val AppTypography = Typography(
    displayLarge = Base.displayLarge.copy(fontWeight = FontWeight.Bold),
    displayMedium = Base.displayMedium.copy(fontWeight = FontWeight.Bold),
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge,
    bodyMedium = Base.bodyMedium,
    bodySmall = Base.bodySmall,
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.Medium),
    labelSmall = Base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * AutoTube AI theme. The app is always dark (near-black background, red accent, amber secondary,
 * teal tertiary) regardless of the system setting. Text uses the system font, which falls back to
 * Noto for Devanagari so Hindi renders correctly.
 */
@Composable
fun AutoTubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
