package app.harvester.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsSwitchRow
import app.harvester.companion.theme.UHTheme

class CustomizeAddressBarActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    var position by remember { mutableStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_ADDRESS_BAR_POSITION, 1)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(id = R.color.bg))
            .statusBarsPadding()
     ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Customize Address Bar",
                color = colorResource(id = R.color.text),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }

        LazyColumn(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
         ) {
            item { SettingsCategory("Position") }
            item {
                Row(
                   modifier = Modifier
                       .fillMaxWidth()
                       .padding(vertical = 4.dp)
                       .background(colorResource(id = R.color.surface), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                       .clickable { 
                           position = 0
                           SettingsPrefs.setInt(context, SettingsPrefs.K_ADDRESS_BAR_POSITION, 0)
                       }
                       .padding(8.dp),
                   verticalAlignment = Alignment.CenterVertically
                ) {
                   RadioButton(
                       selected = position == 0,
                       onClick = null
                   )
                   Text("Top", color = colorResource(id = R.color.text), fontSize = 16.sp)
                }
            }
            item {
                Row(
                   modifier = Modifier
                       .fillMaxWidth()
                       .padding(vertical = 4.dp)
                       .background(colorResource(id = R.color.surface), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                       .clickable { 
                           position = 1
                           SettingsPrefs.setInt(context, SettingsPrefs.K_ADDRESS_BAR_POSITION, 1)
                       }
                       .padding(8.dp),
                   verticalAlignment = Alignment.CenterVertically
                ) {
                   RadioButton(
                       selected = position == 1,
                       onClick = null
                   )
                   Text("Bottom", color = colorResource(id = R.color.text), fontSize = 16.sp)
                }
            }

            item { SettingsCategory("Behavior") }
            item { SettingsSwitchRow("Auto-hide Bar", SettingsPrefs.K_AUTO_HIDE_BAR, default = false) }
            item { SettingsSwitchRow("Swipe for Fullscreen", SettingsPrefs.K_SWIPE_FULLSCREEN, default = false) }
            item { SettingsSwitchRow("Show Full URL", SettingsPrefs.K_SHOW_FULL_URL, default = false) }
        }
    }
}
