package app.harvester.companion.browser.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R

@Composable
fun UHSuggestionsPanel(
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .background(colorResource(id = R.color.bg))
            .padding(top = 4.dp)
    ) {
        items(suggestions) { suggestion ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colorResource(id = R.color.surface_2))
                    .clickable { onSuggestionClick(suggestion) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_search),
                    contentDescription = "Search",
                    tint = colorResource(id = R.color.subtext),
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = suggestion,
                    color = colorResource(id = R.color.text),
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 14.dp).weight(1f)
                )
                Icon(
                    painter = painterResource(id = R.drawable.ic_arrow_out),
                    contentDescription = "Go",
                    tint = colorResource(id = R.color.subtext),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
