package app.harvester.companion.settings.video

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.UHApp
import app.harvester.companion.privacy.UhPrivacyController
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsSwitchRow
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsDivider
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme

class VideoOptionsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        setContent {
            UHTheme {
                val context = LocalContext.current

                UhSettingsScaffold(
                    title = "Video Options",
                    onBack = { finish() },
                ) {
                    SettingsCategory("Playback & Media Controls")
                    UhSettingsCardGroup {
                        SettingsSwitchRow(
                            title = "Background Playback",
                            subtitle = "Keep video and audio playing when minimized or screen is off, with notification & lock-screen player",
                            key = SettingsPrefs.K_BACKGROUND_PLAY,
                            default = true,
                            iconRes = R.drawable.fx_ic_play_24,
                            insideCard = true,
                            onChanged = { UhPrivacyController.applyAllToEngine(context) },
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Picture-in-Picture (PiP)",
                            subtitle = "Float playing fullscreen videos in a mini window when switching apps",
                            key = SettingsPrefs.K_PIP_ENABLED,
                            default = true,
                            iconRes = R.drawable.fx_ic_video_24,
                            insideCard = true,
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Autoplay Videos",
                            subtitle = "Allow websites to play video and audio automatically",
                            key = SettingsPrefs.K_AUTOPLAY,
                            default = false,
                            iconRes = R.drawable.uh_ic_autoplay_24,
                            insideCard = true,
                            onChanged = {
                                (context.applicationContext as? UHApp)?.components?.sitePermissionRules?.notifyRulesChanged()
                            },
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
