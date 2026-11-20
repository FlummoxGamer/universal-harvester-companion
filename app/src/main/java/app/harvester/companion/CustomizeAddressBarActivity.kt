package app.harvester.companion

import android.graphics.Color
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsSwitchRow
import app.harvester.companion.theme.UHTheme

class CustomizeAddressBarActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContent {
            UHTheme {
                CustomizeAddressBarScreen()
            }
        }
    }
}

@Composable
fun CustomizeAddressBarScreen() {
    val context = LocalContext.current
    val isBottom by SettingsPrefs.addressBarBottomFlow(context).collectAsState()
    val position = if (isBottom) 1 else 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Customize Address Bar",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }

        LazyColumn(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            item(key = "cat_position") { SettingsCategory("Position") }
            item(key = "row_pos_top") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable {
                            SettingsPrefs.setInt(context, SettingsPrefs.K_ADDRESS_BAR_POSITION, 0)
                        }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = position == 0,
                        onClick = null
                    )
                    Spacer(modifier = Modifier.size(16.dp))
                    Text("Top", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
                }
            }
            item(key = "row_pos_bottom") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable {
                            SettingsPrefs.setInt(context, SettingsPrefs.K_ADDRESS_BAR_POSITION, 1)
                        }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = position == 1,
                        onClick = null
                    )
                    Spacer(modifier = Modifier.size(16.dp))
                    Text("Bottom", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
                }
            }

            item(key = "cat_behavior") { SettingsCategory("Behavior") }
            item(key = "row_autohide") { SettingsSwitchRow("Auto-hide Bar", SettingsPrefs.K_AUTO_HIDE_BAR, default = false) }
            item(key = "row_swipe") { SettingsSwitchRow("Swipe for Fullscreen", SettingsPrefs.K_SWIPE_FULLSCREEN, default = false) }
            item(key = "row_fullurl") { SettingsSwitchRow("Show Full URL", SettingsPrefs.K_SHOW_FULL_URL, default = false) }
        }
    }
}
