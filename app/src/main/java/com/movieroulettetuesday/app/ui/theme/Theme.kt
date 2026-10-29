package com.movieroulettetuesday.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Brand colors from the iOS asset catalog (BrandTeal, BrandPeach, LaunchBackground, WheelRim). */
object BrandColors {
    val TealLight = Color(0xFF2B7F88)
    val TealDark = Color(0xFF5BC2CC)
    val AccentDark = Color(0xFF3A9CA6)
    val PeachLight = Color(0xFFB86A22)
    val PeachDark = Color(0xFFF7C08A)
    val BackgroundLight = Color(0xFFF1F6F6)
    val BackgroundDark = Color(0xFF0F1618)
    val WheelRimLight = Color(0xFF262626)
    val WheelRimDark = Color(0xFF3A3A3C)
    val Ink = Color(0xFF1D2B2E) // iOS Brand.ink (mrt-shared ASSETS.md)
    /** iOS Brand.jackpotGold (mrt-shared ASSETS.md). Fixed: never theme-tinted, light or dark. */
    val JackpotGold = Color(0xFFF2C14E)
    /** iOS `CleanProColors.pointerLight` / `pointerDark` (shared/data/palette.json). */
    val PointerLight = Color(0xFF2E8691)
    val PointerDark = Color(0xFF5BB8C2)
}

/** Whether [MrtTheme] is rendering dark (follows the in-app appearance override, not just the system). */
val LocalDarkTheme = staticCompositionLocalOf { false }

private val LightColors = lightColorScheme(
    primary = BrandColors.TealLight,
    secondary = BrandColors.PeachLight,
    background = BrandColors.BackgroundLight,
    surface = BrandColors.BackgroundLight,
)

private val DarkColors = darkColorScheme(
    primary = BrandColors.TealDark,
    secondary = BrandColors.PeachDark,
    background = BrandColors.BackgroundDark,
    surface = BrandColors.BackgroundDark,
)

@Composable
fun MrtTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
    }
}
