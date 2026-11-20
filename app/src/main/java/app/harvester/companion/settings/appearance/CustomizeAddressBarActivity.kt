package app.harvester.companion.settings.appearance

import android.graphics.Color
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsSwitchRow
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsDivider
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme

class CustomizeAddressBarActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContent {
            UHTheme {
                CustomizeAddressBarScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
fun CustomizeAddressBarScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val isBottom by SettingsPrefs.addressBarBottomFlow(context).collectAsState()
    val position = if (isBottom) 1 else 0

    UhSettingsScaffold(
        title = "Customize Address Bar",
        onBack = onBack
    ) {
        SettingsCategory("Position")
        UhSettingsCardGroup {
            AddressPositionRow(
                label = "Top",
                selected = position == 0,
                onClick = { SettingsPrefs.setInt(context, SettingsPrefs.K_ADDRESS_BAR_POSITION, 0) }
            )
            UhSettingsDivider()
            AddressPositionRow(
                label = "Bottom",
                selected = position == 1,
                onClick = { SettingsPrefs.setInt(context, SettingsPrefs.K_ADDRESS_BAR_POSITION, 1) }
            )
        }

        SettingsCategory("Behavior")
        UhSettingsCardGroup {
            SettingsSwitchRow(
                title = "Auto-hide Bar",
                key = SettingsPrefs.K_AUTO_HIDE_BAR,
                default = false,
                insideCard = true
            )
            UhSettingsDivider()
            SettingsSwitchRow(
                title = "Swipe for Fullscreen",
                key = SettingsPrefs.K_SWIPE_FULLSCREEN,
                default = false,
                insideCard = true
            )
            UhSettingsDivider()
            SettingsSwitchRow(
                title = "Show Full URL",
                key = SettingsPrefs.K_SHOW_FULL_URL,
                default = false,
                insideCard = true
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun AddressPositionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
