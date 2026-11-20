package app.harvester.companion.search.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.harvester.companion.R
import app.harvester.companion.search.UhSearchViewModel
import app.harvester.companion.search.model.UhSearchListItem as SearchItemModel
import app.harvester.companion.theme.UHTheme

@Composable
fun UhSearchScreen(
    viewModel: UhSearchViewModel,
    isBarBottom: Boolean,
    addressBarContent: @Composable () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    onFillUrl: (String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Guarantee single-back dismissal of search overlay regardless of background tab history
    BackHandler(enabled = true) {
        onDismiss()
    }

    UHTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .navigationBarsPadding()
                .imePadding()
        ) {
            if (!isBarBottom) {
                addressBarContent()

                state.currentTab?.let { currentTab ->
                    UhSearchListItem(
                        item = currentTab,
                        onSubmit = onSubmit,
                        onEditUrl = onFillUrl
                    )
                }

                SearchResultsColumn(
                    items = state.items,
                    clipboardUrl = state.clipboardUrl,
                    modifier = Modifier.weight(1f),
                    onSubmit = onSubmit,
                    onFillUrl = onFillUrl
                )
            } else {
                SearchResultsColumn(
                    items = state.items,
                    clipboardUrl = state.clipboardUrl,
                    modifier = Modifier.weight(1f),
                    onSubmit = onSubmit,
                    onFillUrl = onFillUrl
                )

                state.currentTab?.let { currentTab ->
                    UhSearchListItem(
                        item = currentTab,
                        onSubmit = onSubmit,
                        onEditUrl = onFillUrl
                    )
                }

                addressBarContent()
            }
        }
    }
}

@Composable
private fun SearchResultsColumn(
    items: List<SearchItemModel>,
    clipboardUrl: String?,
    modifier: Modifier = Modifier,
    onSubmit: (String) -> Unit,
    onFillUrl: (String) -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(
                items = items,
                key = { item ->
                    when (item) {
                        is SearchItemModel.Header -> "header_${item.title}"
                        is SearchItemModel.CurrentTab -> "current_tab"
                        is SearchItemModel.Suggestion -> "sug_${item.title}_${item.type}"
                        is SearchItemModel.History -> "hist_${item.url}"
                    }
                }
            ) { item ->
                UhSearchListItem(
                    item = item,
                    onSubmit = { result -> onSubmit(result) },
                    onEditUrl = onFillUrl
                )
            }
        }

        clipboardUrl?.let { url ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.clickable { onFillUrl(url) }
                ) {
                    Box(
                        modifier = Modifier.size(44.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_link),
                            contentDescription = "Paste URL",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
