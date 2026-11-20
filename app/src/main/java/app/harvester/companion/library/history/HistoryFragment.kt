package app.harvester.companion.library.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import app.harvester.companion.R
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme
import app.harvester.companion.utils.SearchHistoryUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.storage.VisitInfo

class HistoryFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = content {
        UHTheme {
            HistoryScreen(
                onNavigateBack = {
                    try {
                        if (!findNavController().popBackStack()) {
                            activity?.onBackPressedDispatcher?.onBackPressed()
                        }
                    } catch (e: Exception) {
                        UHLog.throwable("ui_error", "Failed navigating back from HistoryFragment", e)
                        activity?.onBackPressedDispatcher?.onBackPressed()
                    }
                },
                onOpenUrl = { url ->
                    val components = (requireContext().applicationContext as UHApp).components
                    components.useCases.sessionUseCases.loadUrl(url)
                    try {
                        val navController = findNavController()
                        if (!navController.popBackStack(R.id.browserFragment, false)) {
                            val options = NavOptions.Builder()
                                .setPopUpTo(R.id.historyFragment, true)
                                .build()
                            navController.navigate(R.id.browserFragment, bundleOf("url" to url), options)
                        }
                    } catch (e: Exception) {
                        UHLog.throwable("ui_error", "Failed navigating from History to browser for $url", e)
                    }
                }
            )
        }
    }
}

@Composable
fun HistoryScreen(
    onNavigateBack: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val context = LocalContext.current
    val components = (context.applicationContext as UHApp).components
    var historyItems by remember { mutableStateOf<List<VisitInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showClearDialog by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.US) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val visits = components.placesHistoryStorage.getDetailedVisits(0L, System.currentTimeMillis())
                val filteredVisits = visits.filter { !SearchHistoryUtils.isSearchUrl(context, it.url) }
                historyItems = filteredVisits.sortedByDescending { it.visitTime }
            } catch (e: Exception) {
                UHLog.throwable("history", "Failed to load history", e)
            } finally {
                isLoading = false
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear history?") },
            text = { Text("All browsing history entries will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    coroutineScope.launch {
                        try {
                            components.placesHistoryStorage.deleteEverything()
                            historyItems = emptyList()
                        } catch (e: Exception) {
                            UHLog.throwable("history", "Failed to clear history", e)
                        }
                    }
                }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }

    UhSettingsScaffold(
        title = "History",
        onBack = onNavigateBack,
        scrollable = false,
        actions = {
            if (historyItems.isNotEmpty()) {
                IconButton(onClick = { showClearDialog = true }) {
                    Icon(
                        painter = painterResource(R.drawable.fx_ic_delete_24),
                        contentDescription = "Clear History",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (isLoading) {
                key("loading") { CircularProgressIndicator(modifier = Modifier.align(Alignment.Center)) }
            } else if (historyItems.isEmpty()) {
                key("empty") {
                    Text(
                        text = "No history yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            } else {
                key("list") {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        items(
                            items = historyItems,
                            key = { item -> "${item.visitTime}_${item.url}" }
                        ) { item ->
                            HistoryItemRow(
                                item = item,
                                dateFormat = dateFormat,
                                onClick = { onOpenUrl(item.url) }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryItemRow(
    item: VisitInfo,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = item.title?.ifBlank { item.url } ?: item.url,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = item.url,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = dateFormat.format(Date(item.visitTime)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
