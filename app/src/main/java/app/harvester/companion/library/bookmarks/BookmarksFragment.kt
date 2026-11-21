package app.harvester.companion.library.bookmarks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
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
import app.harvester.companion.ShortcutStore
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.concept.storage.BookmarkInfo
import mozilla.components.concept.storage.BookmarkNode
import mozilla.components.concept.storage.BookmarkNodeType

class BookmarksFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = content {
        UHTheme {
            BookmarksScreen(
                onNavigateBack = {
                    try {
                        if (!findNavController().popBackStack()) {
                            activity?.onBackPressedDispatcher?.onBackPressed()
                        }
                    } catch (e: Exception) {
                        UHLog.throwable("ui_error", "Failed navigating back from BookmarksFragment", e)
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
                                .setPopUpTo(R.id.bookmarksFragment, true)
                                .build()
                            navController.navigate(R.id.browserFragment, bundleOf("url" to url), options)
                        }
                    } catch (e: Exception) {
                        UHLog.throwable("ui_error", "Failed navigating from Bookmarks to browser for $url", e)
                    }
                }
            )
        }
    }
}

@Composable
fun BookmarksScreen(
    onNavigateBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    val components = (context.applicationContext as UHApp).components
    val scope = rememberCoroutineScope()

    var bookmarks by remember { mutableStateOf<List<BookmarkNode>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var editingNode by remember { mutableStateOf<BookmarkNode?>(null) }

    fun loadBookmarks() {
        scope.launch {
            isLoading = true
            val loaded = withContext(Dispatchers.IO) {
                try {
                    val root = components.placesBookmarksStorage
                        .getTree(BookmarkRoot.Mobile.id, recursive = true)
                        .getOrNull()
                    flattenBookmarks(root).sortedByDescending { it.dateAdded }
                } catch (e: Exception) {
                    UHLog.w("bookmarks", "Failed loading bookmarks: ${e.message}")
                    emptyList()
                }
            }
            bookmarks = loaded
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadBookmarks()
    }

    val filtered = remember(bookmarks, searchQuery) {
        val q = searchQuery.trim()
        if (q.isBlank()) bookmarks
        else bookmarks.filter {
            (it.title?.contains(q, ignoreCase = true) == true) ||
                (it.url?.contains(q, ignoreCase = true) == true)
        }
    }

    UhSettingsScaffold(
        title = "Bookmarks",
        subtitle = "${bookmarks.size} saved page${if (bookmarks.size == 1) "" else "s"}",
        onBack = onNavigateBack,
        scrollable = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Search box
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_search_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Search bookmarks…",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 14.sp
                                    )
                                }
                                inner()
                            }
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(26.dp)) {
                            Icon(
                                painter = painterResource(id = R.drawable.fx_ic_close_24),
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                filtered.isEmpty() -> {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.uh_ic_bookmark_24),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(34.dp)
                            )
                            Text(
                                text = if (searchQuery.isBlank()) "No bookmarks yet" else "No matching bookmarks",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (searchQuery.isBlank()) {
                                    "Tap the star icon in the browser menu to save your favorite pages."
                                } else {
                                    "Try another search term."
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 28.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filtered, key = { it.guid }) { node ->
                            val url = node.url.orEmpty()
                            val title = node.title?.ifBlank { url } ?: url
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        if (url.isNotBlank()) onOpenUrl(url)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AsyncImage(
                                            model = ShortcutStore.iconUrlFor(url),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(22.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = title,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = url.removePrefix("https://").removePrefix("http://"),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            cm?.setPrimaryClip(ClipData.newPlainText("url", url))
                                            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.uh_ic_copy_24),
                                            contentDescription = "Copy link",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = { editingNode = node },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.fx_ic_settings_24),
                                            contentDescription = "Edit bookmark",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    components.placesBookmarksStorage.deleteNode(node.guid)
                                                }
                                                bookmarks = bookmarks.filterNot { it.guid == node.guid }
                                                Toast.makeText(context, "Bookmark deleted", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.fx_ic_delete_24),
                                            contentDescription = "Delete bookmark",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val editTarget = editingNode
    if (editTarget != null) {
        var titleInput by remember(editTarget.guid) { mutableStateOf(editTarget.title.orEmpty()) }
        var urlInput by remember(editTarget.guid) { mutableStateOf(editTarget.url.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editingNode = null },
            title = { Text("Edit Bookmark", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cleanUrl = urlInput.trim()
                        if (cleanUrl.isNotBlank()) {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    components.placesBookmarksStorage.updateNode(
                                        editTarget.guid,
                                        BookmarkInfo(
                                            parentGuid = editTarget.parentGuid,
                                            position = editTarget.position,
                                            title = titleInput.trim().ifBlank { cleanUrl },
                                            url = cleanUrl,
                                        )
                                    )
                                }
                                editingNode = null
                                loadBookmarks()
                            }
                        }
                    }
                ) {
                    Text("Save", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingNode = null }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

private fun flattenBookmarks(node: BookmarkNode?): List<BookmarkNode> {
    if (node == null) return emptyList()
    val out = mutableListOf<BookmarkNode>()
    if (node.type == BookmarkNodeType.ITEM && !node.url.isNullOrBlank()) {
        out.add(node)
    }
    node.children?.forEach { child ->
        out.addAll(flattenBookmarks(child))
    }
    return out
}
