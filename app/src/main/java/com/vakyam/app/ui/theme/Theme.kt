package com.vakyam.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * High-contrast palette. The app is dark-only on purpose: bright tiles on a near-black background
 * stay legible at arm's length and the dim screen keeps glare off the user's eyes, which the
 * camera needs to see clearly.
 */
object VakyamColors {
    val Background = Color(0xFF05070A)
    val Surface = Color(0xFF111827)
    val OnDark = Color(0xFFFFFFFF)
    val OnDarkMuted = Color(0xFFB0BEC5)
    val Amber = Color(0xFFFFD54F)
    val Cyan = Color(0xFF00E5FF)
    val Green = Color(0xFF00E676)
    val Red = Color(0xFFFF5252)
    val Ink = Color(0xFF0B1220)
    val CategoryContainer = Color(0xFF12203A)
    val KeywordContainer = Color(0xFF101418)
    val NavigationContainer = Color(0xFF14261A)
    val ActionContainer = Color(0xFF1F2329)
    val Outline = Color(0xFF90A4AE)
}

private val VakyamColorScheme = darkColorScheme(
    primary = VakyamColors.Amber,
    onPrimary = VakyamColors.Ink,
    secondary = VakyamColors.Cyan,
    onSecondary = VakyamColors.Ink,
    background = VakyamColors.Background,
    onBackground = VakyamColors.OnDark,
    surface = VakyamColors.Surface,
    onSurface = VakyamColors.OnDark,
    onSurfaceVariant = VakyamColors.OnDarkMuted,
    error = VakyamColors.Red,
    outline = VakyamColors.Outline,
)

@Composable
fun VakyamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VakyamColorScheme, content = content)
}
