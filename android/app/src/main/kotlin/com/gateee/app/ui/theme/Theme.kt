package com.gateee.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF0B3D91),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001A43),
    secondary = Color(0xFFB8860B),
    secondaryContainer = Color(0xFFFFE08A),
    tertiary = Color(0xFF2E7D32),
    background = Color(0xFFF8F9FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFE6E9F2),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFAFC6FF),
    onPrimary = Color(0xFF002D6C),
    primaryContainer = Color(0xFF1E4A9E),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFFFC93C),
    secondaryContainer = Color(0xFF5A4300),
    tertiary = Color(0xFF81C784),
    background = Color(0xFF0F1218),
    surface = Color(0xFF161A22),
    surfaceVariant = Color(0xFF2A303C),
)

/** GATE palette colours (answered, not answered, marked, not visited). */
object PaletteColors {
    val answered = Color(0xFF2E9E46)
    val notAnswered = Color(0xFFD9442B)
    val marked = Color(0xFF6C3FB5)
    val notVisited = Color(0xFF9EA4AE)
    val correct = Color(0xFF2E9E46)
    val wrong = Color(0xFFD9442B)
}

@Composable
fun GateTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
