package pl.lk.toyotacompanion.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
private const val TOYOTA_RED = 0xFFEB0A1E
private const val TOYOTA_RED_DARK = 0xFFFFB4AB

/** Toyota red on white; the rest of the tones follow the Material 3 roles. */
private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(TOYOTA_RED),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF410005),
    secondary = Color(0xFF775652),
    surface = Color(0xFFFFFBFF),
    background = Color(0xFFFFFBFF),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(TOYOTA_RED_DARK),
    onPrimary = Color(0xFF690009),
    primaryContainer = Color(0xFF920011),
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = Color(0xFFE7BDB7),
    surface = Color(0xFF211A19),
    background = Color(0xFF1A1110),
)

/**
 * Material 3 with the system light/dark theme. Android 12+ uses the wallpaper
 * palette (dynamic color); below it the app's own Toyota-red schemes (minSdk 29).
 * The window backgrounds in res/values{,-night}/themes.xml match these colors, so
 * the launch frame and the area behind the transparent system bars look the same.
 */
@Composable
fun ToyotaTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (isSystemInDarkTheme()) {
        DarkScheme
    } else {
        LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

