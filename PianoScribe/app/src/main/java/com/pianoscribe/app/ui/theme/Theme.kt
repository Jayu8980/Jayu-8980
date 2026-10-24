package com.pianoscribe.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.pianoscribe.app.data.ThemeMode

private val Light = lightColorScheme(
    primary = Color(0xFF3550B5), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF), onPrimaryContainer = Color(0xFF001257),
    secondary = Color(0xFF8C5000), secondaryContainer = Color(0xFFFFDCBE),
    tertiary = Color(0xFF006A60),
    background = Color(0xFFFBFAFF), surface = Color(0xFFFBFAFF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB8C3FF), onPrimary = Color(0xFF00218B),
    primaryContainer = Color(0xFF19369C), onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFFFB871), secondaryContainer = Color(0xFF6A3B00),
    tertiary = Color(0xFF53DBC9),
    background = Color(0xFF121318), surface = Color(0xFF121318),
)

/** Colours used by the music views (hands, warnings), tuned per theme. */
@Immutable
data class MusicColors(
    val rightHand: Color,
    val leftHand: Color,
    val warning: Color,
    val good: Color,
    val gridLine: Color,
    val barLine: Color,
    val blackKeyRow: Color,
    val whiteKeyRow: Color,
    val playhead: Color,
    val outOfKey: Color,
)

val LightMusic = MusicColors(
    rightHand = Color(0xFF4267D2), leftHand = Color(0xFFE67E22), warning = Color(0xFFD35400), good = Color(0xFF2E9E5B),
    gridLine = Color(0x14000000), barLine = Color(0x33000000), blackKeyRow = Color(0xFFECEDF2), whiteKeyRow = Color(0xFFFAFAFC),
    playhead = Color(0xFFE53935), outOfKey = Color(0xFF9C27B0),
)
val DarkMusic = MusicColors(
    rightHand = Color(0xFF7FA0FF), leftHand = Color(0xFFFFA94D), warning = Color(0xFFFF8A3D), good = Color(0xFF5BD68A),
    gridLine = Color(0x1AFFFFFF), barLine = Color(0x40FFFFFF), blackKeyRow = Color(0xFF1B1D24), whiteKeyRow = Color(0xFF23252D),
    playhead = Color(0xFFFF5252), outOfKey = Color(0xFFE040FB),
)

val LocalMusicColors = staticCompositionLocalOf { LightMusic }

@Composable
fun PianoScribeTheme(mode: ThemeMode, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalMusicColors provides if (dark) DarkMusic else LightMusic) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
