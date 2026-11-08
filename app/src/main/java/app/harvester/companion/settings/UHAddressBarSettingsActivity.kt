package app.harvester.companion.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.theme.UHTheme

class UHAddressBarSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UHTheme {
                AddressBarSettingsScreen()
            }
        }
    }
}

@Composable
fun AddressBarSettingsScreen() {
    val context = LocalContext.current
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(id = R.color.bg))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Address Bar",
                color = colorResource(id = R.color.text),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }

        LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
            item {
                SettingsSwitchRow("Show Full URL", SettingsPrefs.K_SHOW_FULL_URL, default = false)
            }
        }
    }
}

@Composable
fun SettingsSwitchRow(title: String, key: String, default: Boolean) {
    val context = LocalContext.current
    var isChecked by remember { mutableStateOf(SettingsPrefs.getBool(context, key, default)) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colorResource(id = R.color.surface))
            .clickable { 
                isChecked = !isChecked
                SettingsPrefs.setBool(context, key, isChecked)
            }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = colorResource(id = R.color.text),
            fontSize = 16.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = isChecked,
            onCheckedChange = { 
                isChecked = it
                SettingsPrefs.setBool(context, key, it)
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = colorResource(id = R.color.bg),
                checkedTrackColor = colorResource(id = R.color.accent)
            )
        )
    }
}
