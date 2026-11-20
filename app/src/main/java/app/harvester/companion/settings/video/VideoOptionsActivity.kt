package app.harvester.companion.settings.video

import android.graphics.Color
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsSwitchRow
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme

class VideoOptionsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            UHTheme {
                UhSettingsScaffold(
                    title = "Video Options",
                    onBack = { finish() }
                ) {
                    SettingsCategory("Playback")
                    UhSettingsCardGroup {
                        SettingsSwitchRow(
                            title = "Autoplay videos",
                            subtitle = "Allow websites to play media automatically",
                            key = SettingsPrefs.K_AUTOPLAY,
                            default = false,
                            iconRes = R.drawable.fx_ic_video_24,
                            insideCard = true
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
