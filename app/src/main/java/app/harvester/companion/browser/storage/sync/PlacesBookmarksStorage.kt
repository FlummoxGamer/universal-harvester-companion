package app.harvester.companion.browser.storage.sync

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import mozilla.appservices.places.PlacesApi
import mozilla.components.concept.storage.BookmarkInfo
import mozilla.components.concept.storage.BookmarkNode
import mozilla.components.concept.storage.BookmarksStorage
import mozilla.components.concept.storage.bookmarks.InsertableBookmarkTreeRoot
import mozilla.components.concept.sync.SyncableStore
import mozilla.components.concept.toolbar.AutocompleteProvider
import mozilla.components.concept.toolbar.AutocompleteResult
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.ktx.kotlin.toWellFormed
import mozilla.components.support.utils.doesUrlStartsWithText
import mozilla.components.support.utils.segmentAwareDomainMatch

@VisibleForTesting internal const val BOOKMARKS_AUTOCOMPLETE_SOURCE_NAME = "placesBookmarks"
private const val BOOKMARKS_AUTOCOMPLETE_QUERY_LIMIT = 20

open class PlacesBookmarksStorage(
    context: Context,
    override val autocompletePriority: Int = 0,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
) : PlacesStorage(context), BookmarksStorage, SyncableStore, AutocompleteProvider {

    override val logger = Logger("PlacesBookmarksStorage")

    override suspend fun getTree(guid: String, recursive: Boolean): Result<BookmarkNode?> {
        return withContext(readScope.coroutineContext) {
            Result.runCatching { reader.getBookmarksTree(guid, recursive)?.asBookmarkNode() }
        }
    }

    override suspend fun getBookmark(guid: String): Result<BookmarkNode?> {
        return withContext(readScope.coroutineContext) {
            Result.runCatching { reader.getBookmark(guid)?.asBookmarkNode() }
        }
    }

    override suspend fun getBookmarksWithUrl(url: String): Result<List<BookmarkNode>> {
        return withContext(readScope.coroutineContext) {
            Result.runCatching { reader.getBookmarksWithURL(url).map { it.asBookmarkNode() } }
        }
    }

    override suspend fun searchBookmarks(query: String, limit: Int): Result<List<BookmarkNode>> {
        return withContext(readScope.coroutineContext) {
            Result.runCatching { reader.searchBookmarks(query, limit).map { it.asBookmarkNode() } }
        }
    }

    override suspend fun getRecentBookmarks(limit: Int, maxAge: Long?, currentTime: Long): Result<List<BookmarkNode>> {
        return withContext(readScope.coroutineContext) {
            val threshold = if (maxAge != null) currentTime - maxAge else 0
            Result.runCatching {
                reader.getRecentBookmarks(limit).map { it.asBookmarkNode() }.filter { it.dateAdded >= threshold }
            }
        }
    }

    override suspend fun addItem(parentGuid: String, url: String, title: String, position: UInt?): Result<String> {
        return withContext(writeScope.coroutineContext) {
            Result.runCatching { writer.createBookmarkItem(parentGuid, url, title, position) }
        }
    }

    override suspend fun addFolder(parentGuid: String, title: String, position: UInt?): Result<String> {
        return withContext(writeScope.coroutineContext) {
            Result.runCatching { writer.createFolder(parentGuid, title, position) }
        }
    }

    override suspend fun addSeparator(parentGuid: String, position: UInt?): Result<String> {
        return withContext(writeScope.coroutineContext) {
            Result.runCatching { writer.createSeparator(parentGuid, position) }
        }
    }

    override suspend fun updateNode(guid: String, info: BookmarkInfo): Result<Unit> {
        return withContext(writeScope.coroutineContext) {
            Result.runCatching {
                writer.updateBookmark(guid, info.parentGuid, info.position, info.title, info.url)
            }
        }
    }

    override suspend fun deleteNode(guid: String): Result<Boolean> =
        withContext(writeScope.coroutineContext) {
            Result.runCatching { writer.deleteBookmarkNode(guid) }
        }

    override suspend fun countBookmarksInTrees(guids: List<String>): UInt {
        return withContext(readScope.coroutineContext) {
            handlePlacesExceptions("countBookmarksInTrees", 0U) { reader.countBookmarksInTrees(guids) }
        }
    }

    override fun registerWithSyncManager() {
        places.registerWithSyncManager()
    }

    override suspend fun getAutocompleteSuggestion(query: String): AutocompleteResult? =
        searchBookmarks(query, BOOKMARKS_AUTOCOMPLETE_QUERY_LIMIT).getOrNull()?.let { bookmarks ->
            val bookmarkUrl =
                bookmarks.mapNotNull { it.url }.firstOrNull { doesUrlStartsWithText(it, query) } ?: return null

            val resultText = segmentAwareDomainMatch(query, arrayListOf(bookmarkUrl))
            resultText?.let {
                AutocompleteResult(
                    input = query,
                    text = it.matchedSegment,
                    url = it.url,
                    source = BOOKMARKS_AUTOCOMPLETE_SOURCE_NAME,
                    totalItems = 1,
                )
            }
        }

    override suspend fun insertTree(tree: InsertableBookmarkTreeRoot): Result<String> {
        return withContext(writeDispatcher) {
            runCatching {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { writer.interrupt() }
                    val guid = writer.insertBookmarkTree(tree.rootFolder.toPlacesItem(tree.parentGuid).f)
                    continuation.resume(guid)
                }
            }
        }
    }
}
