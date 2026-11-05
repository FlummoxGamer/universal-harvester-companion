package app.harvester.companion.browser.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import app.harvester.companion.SearchEnginePrefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchEnginePicker(onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val current = SearchEnginePrefs.getEngine(context)
    val engines = SearchEnginePrefs.all(context)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = Color(0xFF171B21)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Search Engine", color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(bottom = 16.dp))
            engines.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            SearchEnginePrefs.setEngine(context, entry.id)
                            onSelect(entry.id)
                            onDismiss()
                        }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = entry.iconUrl,
                        contentDescription = entry.name,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = entry.name,
                        color = if (entry.id == current) Color(0xFF457B9D) else Color.White,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(start = 16.dp).weight(1f)
                    )
                }
            }
        }
    }
}
