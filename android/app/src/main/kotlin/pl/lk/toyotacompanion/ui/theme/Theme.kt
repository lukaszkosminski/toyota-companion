package pl.lk.toyotacompanion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
// Toyota red #EB0A1E darkened so text and text buttons in it reach WCAG AA 4.5:1 on every card background
// (3.45-3.97:1 before, docs/SPEC.md §10.4); white on it stays above 6:1. The launcher icon keeps #EB0A1E.
private const val TOYOTA_RED = 0xFFC8001A
private const val TOYOTA_RED_DARK = 0xFFFFB4AB

/** Toyota red on white; the rest of the tones follow the Material 3 roles. */
private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(TOYOTA_RED),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF410005),
    secondary = Color(0xFF2F7DE1),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8E6FF),
    onSecondaryContainer = Color(0xFF001B3F),
    tertiary = Color(0xFF2E9E5B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC6F2D6),
    onTertiaryContainer = Color(0xFF00210F),
    surface = Color(0xFFFFFBFF),
    background = Color(0xFFFFFBFF),
    surfaceContainer = Color(0xFFF6EDEC),
    surfaceContainerHigh = Color(0xFFF1E6E5),
    surfaceContainerHighest = Color(0xFFE9DDDB),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(TOYOTA_RED_DARK),
    onPrimary = Color(0xFF690009),
    primaryContainer = Color(0xFF920011),
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = Color(0xFFA9C7FF),
    onSecondary = Color(0xFF002F65),
    secondaryContainer = Color(0xFF16468C),
    onSecondaryContainer = Color(0xFFD8E6FF),
    tertiary = Color(0xFF7FDBA0),
    onTertiary = Color(0xFF00391C),
    tertiaryContainer = Color(0xFF005229),
    onTertiaryContainer = Color(0xFFC6F2D6),
    surfaceContainer = Color(0xFF2B2221),
    surfaceContainerHigh = Color(0xFF362C2B),
    surfaceContainerHighest = Color(0xFF413736),
    surface = Color(0xFF211A19),
    background = Color(0xFF1A1110),
)

/**
 * Material 3 with the system light/dark theme. Fixed Toyota-red/blue/green schemes (no
 * wallpaper palette, which made the screens grey).
 * The window backgrounds in res/values{,-night}/themes.xml match these colors, so
 * the launch frame and the area behind the transparent system bars look the same.
 */
@Composable
fun ToyotaTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme
    MaterialTheme(colorScheme = scheme, content = content)
}

