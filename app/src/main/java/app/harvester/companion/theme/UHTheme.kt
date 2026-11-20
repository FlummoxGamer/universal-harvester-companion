package app.harvester.companion.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.harvester.companion.ThemePrefs

private val DarkColorScheme = darkColorScheme(
    primary = UH_Accent,
    background = UH_Dark_Bg,
    surface = UH_Dark_Surface,
    surfaceVariant = UH_Dark_Surface2,
    secondaryContainer = UH_Dark_Surface3,
    surfaceContainer = UH_Dark_Surface2,
    surfaceContainerHigh = UH_Dark_Surface3,
    onPrimary = Color.White,
    onBackground = UH_Dark_Text,
    onSurface = UH_Dark_Text,
    onSurfaceVariant = UH_Dark_Subtext,
    error = UH_Dark_Red,
    outline = UH_Dark_Border
)

private val LightColorScheme = lightColorScheme(
    primary = UH_Accent,
    background = UH_Light_Bg,
    surface = UH_Light_Surface,
    surfaceVariant = UH_Light_Surface2,
    secondaryContainer = UH_Light_Surface3,
    surfaceContainer = UH_Light_Surface2,
    surfaceContainerHigh = UH_Light_Surface3,
    onPrimary = UH_Light_Bg,
    onBackground = UH_Light_Text,
    onSurface = UH_Light_Text,
    onSurfaceVariant = UH_Light_Subtext,
    error = UH_Light_Red,
    outline = UH_Light_Border
)

@Composable
fun UHTheme(
    lightStatusBarOverride: Boolean? = null,
    lightNavigationBarOverride: Boolean? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val mode by ThemePrefs.modeFlow(context).collectAsState()
    val systemDark = isSystemInDarkTheme()
    val isDark = when (mode) {
        ThemePrefs.MODE_DARK -> true
        ThemePrefs.MODE_LIGHT -> false
        else -> systemDark
    }
    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        val useLightStatus = lightStatusBarOverride ?: !isDark
        val useLightNav = lightNavigationBarOverride ?: !isDark
        val bgArgb = colorScheme.background.toArgb()
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                if (lightStatusBarOverride == null && lightNavigationBarOverride == null) {
                    window.decorView.setBackgroundColor(bgArgb)
                }
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = useLightStatus
                controller.isAppearanceLightNavigationBars = useLightNav
            }
        }
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
