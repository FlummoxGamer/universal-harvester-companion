package app.harvester.companion.settings.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R

@Composable
fun SettingsCategory(title: String) {
    Text(
        text = title.uppercase(),
        color = colorResource(id = R.color.subtext),
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
    )
}
