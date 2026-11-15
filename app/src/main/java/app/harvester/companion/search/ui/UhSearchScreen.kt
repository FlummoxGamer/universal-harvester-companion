package app.harvester.companion.search.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.search.UhSearchViewModel
import app.harvester.companion.theme.UHTheme

@Composable
fun UhSearchScreen(
    viewModel: UhSearchViewModel,
    isBarBottom: Boolean,
    addressBarContent: @Composable () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    val state by viewModel.state.collectAsState()

    BackHandler {
        onDismiss()
    }

    UHTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .imePadding()
        ) {
            if (!isBarBottom) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    addressBarContent()
                }
                SearchContent(
                    state = state,
                    isBarBottom = isBarBottom,
                    modifier = Modifier.weight(1f),
                    onSubmit = onSubmit,
                    viewModel = viewModel
                )
            } else {
                SearchContent(
                    state = state,
                    isBarBottom = isBarBottom,
                    modifier = Modifier.weight(1f).statusBarsPadding(),
                    onSubmit = onSubmit,
                    viewModel = viewModel
                )
                addressBarContent()
            }
        }
    }
}

@Composable
private fun SearchContent(
    state: app.harvester.companion.search.UhSearchState,
    isBarBottom: Boolean,
    modifier: Modifier = Modifier,
    onSubmit: (String) -> Unit,
    viewModel: UhSearchViewModel
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(state.items) { item ->
                UhSearchListItem(
                    item = item,
                    onClick = { result ->
                        viewModel.onQueryChanged(result)
                        onSubmit(result)
                    }
                )
            }
        }

        state.clipboardUrl?.let { url ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .clickable { onSubmit(url) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_link),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Fill link from clipboard",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }

        if (isBarBottom) {
            Box(modifier = Modifier.navigationBarsPadding())
        }
    }
}
