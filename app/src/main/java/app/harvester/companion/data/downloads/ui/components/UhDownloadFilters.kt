package app.harvester.companion.data.downloads.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.harvester.companion.R
import app.harvester.companion.data.downloads.UhFileItem

@Composable
fun UhDownloadFilters(
    selectedFilter: UhFileItem.ContentTypeFilter,
    filters: List<UhFileItem.ContentTypeFilter>,
    onFilterSelected: (UhFileItem.ContentTypeFilter) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        items(filters) { filter ->
            FilterChip(
                selected = selectedFilter == filter,
                onClick = { onFilterSelected(filter) },
                label = { Text(stringResource(id = filter.stringRes)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = colorResource(id = R.color.accent),
                    selectedLabelColor = colorResource(id = R.color.bg),
                    containerColor = colorResource(id = R.color.surface_2),
                    labelColor = colorResource(id = R.color.text)
                )
            )
        }
    }
}
