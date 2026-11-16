package app.harvester.companion.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.harvester.companion.ThemePrefs

private val DarkColorScheme = darkColorScheme(
    primary = UH_Accent,
    background = UH_Dark_Bg,
    surface = UH_Dark_Surface,
    surfaceVariant = UH_Dark_Surface2,
    onPrimary = UH_Accent_Strong,
    onBackground = UH_Dark_Text,
    onSurface = UH_Dark_Text,
    onSurfaceVariant = UH_Dark_Text,
    error = UH_Dark_Red,
    outline = UH_Dark_Border
)

private val LightColorScheme = lightColorScheme(
    primary = UH_Accent,
    background = UH_Light_Bg,
    surface = UH_Light_Surface,
    surfaceVariant = UH_Light_Surface2,
    onPrimary = UH_Light_Bg,
    onBackground = UH_Light_Text,
    onSurface = UH_Light_Text,
    onSurfaceVariant = UH_Light_Text,
    error = UH_Light_Red,
    outline = UH_Light_Border
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
