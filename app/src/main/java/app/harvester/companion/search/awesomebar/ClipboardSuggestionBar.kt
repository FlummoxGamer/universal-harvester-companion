package app.harvester.companion.search.awesomebar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R

@Composable
fun ClipboardSuggestionBar(
    shouldUseBottomToolbar: Boolean,
    modifier: Modifier = Modifier,
    backgroundColor: Color = colorResource(id = R.color.bg),
    onClick: () -> Unit,
) {
    Surface(
        color = backgroundColor,
        contentColor = colorResource(id = R.color.text),
    ) {
        Box(modifier = modifier) {
            Row(
                modifier = Modifier.clickable(onClick = onClick).padding(8.dp).height(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_link_24),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = colorResource(id = R.color.text)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "Clipboard URL",
                    color = colorResource(id = R.color.text),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider(
                modifier = when (shouldUseBottomToolbar) {
                    true -> Modifier.align(Alignment.TopCenter)
                    false -> Modifier.align(Alignment.BottomCenter)
                },
                color = colorResource(id = R.color.border)
            )
        }
    }
}
