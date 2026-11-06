package app.harvester.companion.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.harvester.companion.ThemePrefs

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF457B9D),
    background = Color(0xFF0A0F1A),
    surface = Color(0xFF171B21),
    onPrimary = Color(0xFF0A0F1A),
    onBackground = Color(0xFFE6E9EE),
    onSurface = Color(0xFFE6E9EE)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF457B9D),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF4F6F9),
    onPrimary = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1D3557),
    onSurface = Color(0xFF1D3557)
)

@Composable
fun UHTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val isDark = when (ThemePrefs.getMode(context)) {
        ThemePrefs.MODE_DARK -> true
        ThemePrefs.MODE_LIGHT -> false
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme
    MaterialTheme(colorScheme = colorScheme, content = content)
}
