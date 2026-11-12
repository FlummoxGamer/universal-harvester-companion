package app.harvester.companion.browser.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import app.harvester.companion.R
import app.harvester.companion.SearchEnginePrefs

@Composable
fun UHSearchEnginePicker(onDismiss: () -> Unit, onSelect: () -> Unit) {
    val context = LocalContext.current
    val current = SearchEnginePrefs.getEngine(context)
    val engines = SearchEnginePrefs.all(context)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = colorResource(id = R.color.surface_2),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Search Engine",
                    color = colorResource(id = R.color.text),
                    fontSize = 20.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                LazyColumn {
                    items(engines) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (entry.id == current) colorResource(id = R.color.surface_3) else Color.Transparent)
                                .clickable {
                                    SearchEnginePrefs.setEngine(context, entry.id)
                                    onSelect()
                                    onDismiss()
                                }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = entry.iconUrl,
                                contentDescription = entry.name,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = entry.name,
                                color = if (entry.id == current) colorResource(id = R.color.accent) else colorResource(id = R.color.text),
                                fontSize = 16.sp,
                                modifier = Modifier.padding(start = 16.dp).weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}
