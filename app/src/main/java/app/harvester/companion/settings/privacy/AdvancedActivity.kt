package app.harvester.companion.settings.privacy

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsRow
import app.harvester.companion.settings.components.SettingsSwitchRow
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsDivider
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme

class AdvancedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            UHTheme {
                val context = LocalContext.current

                UhSettingsScaffold(
                    title = "Advanced",
                    onBack = { finish() }
                ) {
                    SettingsCategory("Protections")
                    UhSettingsCardGroup {
                        SettingsSwitchRow(
                            title = "Safe Browsing",
                            key = SettingsPrefs.K_ADV_SAFE_BROWSING,
                            default = false,
                            insideCard = true
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Do Not Track",
                            key = SettingsPrefs.K_ADV_DNT,
                            default = false,
                            insideCard = true
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Auto De-AMP",
                            key = SettingsPrefs.K_ADV_DEAMP,
                            default = false,
                            insideCard = true
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Global Privacy Control",
                            key = SettingsPrefs.K_ADV_GPC,
                            default = false,
                            insideCard = true
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Secure DNS",
                            key = SettingsPrefs.K_ADV_SECURE_DNS,
                            default = false,
                            insideCard = true
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "WebRTC IP Protection",
                            iconRes = R.drawable.fx_ic_shield_lock_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = {
                                Toast.makeText(context, "WebRTC IP Protection coming soon", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
