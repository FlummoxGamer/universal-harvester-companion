package app.harvester.companion.browser.menu

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.DownloadsActivity
import app.harvester.companion.HistoryActivity
import app.harvester.companion.R
import app.harvester.companion.SettingsActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMenuSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colorResource(id = R.color.surface_2)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp, 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colorResource(id = R.color.subtext))
                    .align(Alignment.CenterHorizontally)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colorResource(id = R.color.surface)),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MenuIconItem("History", R.drawable.ic_history, Modifier.weight(1f)) {
                    onDismiss()
                    context.startActivity(Intent(context, HistoryActivity::class.java))
                }
                MenuIconItem("Downloads", R.drawable.ic_downloads, Modifier.weight(1f)) {
                    onDismiss()
                    context.startActivity(Intent(context, DownloadsActivity::class.java))
                }
                MenuIconItem("Settings", R.drawable.ic_settings, Modifier.weight(1f)) {
                    onDismiss()
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                }
            }
        }
    }
}

@Composable
private fun MenuIconItem(label: String, iconRes: Int, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = label,
            tint = colorResource(id = R.color.text),
            modifier = Modifier.padding(top = 16.dp).size(24.dp)
        )
        Text(
            text = label,
            color = colorResource(id = R.color.text),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
