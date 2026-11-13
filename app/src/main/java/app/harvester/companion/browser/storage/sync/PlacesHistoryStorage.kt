package app.harvester.companion.browser.storage.sync

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.withContext
import mozilla.appservices.places.PlacesApi
import mozilla.appservices.places.PlacesReaderConnection
import mozilla.appservices.places.uniffi.VisitObservation
import mozilla.components.concept.base.crash.CrashReporting
import mozilla.components.concept.storage.FrecencyThresholdOption
import mozilla.components.concept.storage.HistoryHighlight
import mozilla.components.concept.storage.HistoryHighlightWeights
import mozilla.components.concept.storage.HistoryMetadata
import mozilla.components.concept.storage.HistoryMetadataKey
import mozilla.components.concept.storage.HistoryMetadataObservation
import mozilla.components.concept.storage.HistoryMetadataStorage
import mozilla.components.concept.storage.HistoryStorage
import mozilla.components.concept.storage.PageObservation
import mozilla.components.concept.storage.PageVisit
import mozilla.components.concept.storage.RedirectSource
import mozilla.components.concept.storage.SearchResult
import mozilla.components.concept.storage.TopFrecentSiteInfo
import mozilla.components.concept.storage.VisitInfo
import mozilla.components.concept.storage.VisitType
import mozilla.components.concept.storage.constraints
import mozilla.components.concept.storage.periodicStorageWorkRequest
import mozilla.components.concept.sync.SyncableStore
import mozilla.components.concept.toolbar.AutocompleteProvider
import mozilla.components.concept.toolbar.AutocompleteResult
import mozilla.components.support.base.log.logger.Logger
import app.harvester.companion.utils.toWellFormed
import mozilla.components.support.utils.segmentAwareDomainMatch

private const val AUTOCOMPLETE_SOURCE_NAME = "placesHistory"

open class PlacesHistoryStorage(
    private val context: Context,
    crashReporter: CrashReporting? = null,
    override val autocompletePriority: Int = 0,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
) : PlacesStorage(context, crashReporter), HistoryStorage, HistoryMetadataStorage, SyncableStore, AutocompleteProvider {
    @VisibleForTesting internal val autocompleteReader: PlacesReaderConnection by lazy { places.newReader() }

    override val logger = Logger("PlacesHistoryStorage")

    override suspend fun recordVisit(uri: String, visit: PageVisit) {
        if (!canAddUri(uri)) {
            logger.debug("Not recording visit (canAddUri=false) for: $uri")
            return
        }
        withContext(writeScope.coroutineContext) {
            handlePlacesExceptions("recordVisit") {
                places
                    .writer()
                    .noteObservation(
                        VisitObservation(
                            uri,
                            visitType = visit.visitType.into(),
                            isRedirectSource = visit.redirectSource != null,
                            isPermanentRedirectSource = visit.redirectSource == RedirectSource.PERMANENT,
                        )
                    )
            }
        }
    }

    override suspend fun recordObservation(uri: String, observation: PageObservation) {
        if (!canAddUri(uri)) {
            logger.debug("Not recording observation (canAddUri=false) for: $uri")
            return
        }
        withContext(writeScope.coroutineContext) {
            handlePlacesExceptions("recordObservation") {
                places
                    .writer()
                    .noteObservation(
                        VisitObservation(
                            url = uri,
                            visitType = null,
                            title = observation.title,
                            previewImageUrl = observation.previewImageUrl,
                        )
                    )
            }
        }
    }

    override suspend fun getVisited(uris: List<String>): List<Boolean> {
        return withContext(readScope.coroutineContext) {
            handlePlacesExceptions("getVisited", default = uris.map { false }) {
                places.reader().getVisited(uris)
            }
        }
    }

    override suspend fun getVisited(): List<String> {
        return withContext(readScope.coroutineContext) {
            handlePlacesExceptions("getVisited", default = emptyList()) {
                places.reader().getVisitedUrlsInRange(start = 0, end = currentTimeMillis(), includeRemote = true)
            }
        }
    }

    override suspend fun getDetailedVisits(start: Long, end: Long, excludeTypes: List<VisitType>): List<VisitInfo> {
        return withContext(readScope.coroutineContext) {
            handlePlacesExceptions("getDetailedVisits", default = emptyList()) {
                places.reader().getVisitInfos(start, end, excludeTypes.map { it.into() }).map { it.into() }
            }
        }
    }

    override suspend fun getVisitsPaginated(offset: Long, count: Long, excludeTypes: List<VisitType>): List<VisitInfo> {
        return withContext(readScope.coroutineContext) {
            handlePlacesExceptions("getVisitsPaginated", default = emptyList()) {
                places.reader().getVisitPage(offset, count, excludeTypes.map { it.into() }).map { it.into() }
            }
        }
    }

    override suspend fun getTopFrecentSites(numItems: Int, frecencyThreshold: FrecencyThresholdOption): List<TopFrecentSiteInfo> {
        if (numItems <= 0) return emptyList()
        return withContext(readScope.coroutineContext) {
            handlePlacesExceptions("getTopFrecentSites", default = emptyList()) {
                places.reader().getTopFrecentSiteInfos(numItems, frecencyThreshold.into()).map { it.into() }
            }
        }
    }

    override fun getSuggestions(query: String, limit: Int): List<SearchResult> {
        require(limit >= 0) { "Limit must be a positive integer" }
        return handlePlacesExceptions("getSuggestions", default = emptyList()) {
            places.reader().queryAutocomplete(query, limit = limit).map {
                SearchResult(it.url, it.url, it.frecency.toInt(), it.title)
            }
        }
    }

    override suspend fun getAutocompleteSuggestion(query: String): AutocompleteResult? {
        val url = handlePlacesExceptions("getAutoCompleteSuggestions", default = null) {
            autocompleteReader.interrupt()
            autocompleteReader.matchUrl(query)
        } ?: return null

        val resultText = segmentAwareDomainMatch(query, arrayListOf(url))
        return resultText?.let {
            AutocompleteResult(input = query, text = it.matchedSegment, url = it.url, source = AUTOCOMPLETE_SOURCE_NAME, totalItems = 1)
        }
    }

    override suspend fun deleteEverything() {
        withContext(writeScope.coroutineContext) {
            handlePlacesExceptions("deleteEverything") { places.writer().deleteEverything() }
        }
    }

    override suspend fun deleteVisitsSince(since: Long) {
        withContext(writeScope.coroutineContext) {
            handlePlacesExceptions("deleteVisitsSince") { places.writer().deleteVisitsSince(since) }
        }
    }

    override suspend fun deleteVisitsBetween(startTime: Long, endTime: Long) {
        withContext(writeScope.coroutineContext) {
            handlePlacesExceptions("deleteVisitsBetween") { places.writer().deleteVisitsBetween(startTime, endTime) }
        }
    }

    override suspend fun deleteVisitsFor(url: String) {
        withContext(writeScope.coroutineContext) {
            handlePlacesExceptions("deleteVisitsFor") { places.writer().deleteVisitsFor(url) }
        }
    }

    override suspend fun deleteVisit(url: String, timestamp: Long) {
        withContext(writeScope.coroutineContext) {
            handlePlacesExceptions("deleteVisit") { places.writer().deleteVisit(url, timestamp) }
        }
    }

    override fun registerStorageMaintenanceWorker() {
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                PlacesHistoryStorageWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicStorageWorkRequest<PlacesHistoryStorageWorker>(tag = PlacesHistoryStorageWorker.UNIQUE_NAME) {
                    constraints {
                        setRequiresBatteryNotLow(true)
                        setRequiresDeviceIdle(true)
                    }
                },
            )
    }

    override fun registerWithSyncManager() {
        return places.registerWithSyncManager()
    }

    override suspend fun getLatestHistoryMetadataForUrl(url: String): HistoryMetadata? {
        return handlePlacesExceptions("getLatestHistoryMetadataForUrl", default = null) {
            places.reader().getLatestHistoryMetadataForUrl(url)?.into()
        }
    }

    override suspend fun getHistoryMetadataSince(since: Long): List<HistoryMetadata> {
        return handlePlacesExceptions("getHistoryMetadataSince", default = emptyList()) {
            places.reader().getHistoryMetadataSince(since).into()
        }
    }

    override suspend fun getHistoryMetadataBetween(start: Long, end: Long): List<HistoryMetadata> {
        return handlePlacesExceptions("getHistoryMetadataBetween", default = emptyList()) {
            places.reader().getHistoryMetadataBetween(start, end).into()
        }
    }

    override suspend fun queryHistoryMetadata(query: String, limit: Int): List<HistoryMetadata> {
        return handlePlacesExceptions("queryHistoryMetadata", default = emptyList()) {
            places.reader().queryHistoryMetadata(query, limit).into()
        }
    }

    override suspend fun getHistoryHighlights(weights: HistoryHighlightWeights, limit: Int): List<HistoryHighlight> {
        return handlePlacesExceptions("getHistoryHighlights", default = emptyList()) {
            places.reader().getHighlights(weights.into(), limit).intoHighlights()
        }
    }

    override suspend fun noteHistoryMetadataObservation(key: HistoryMetadataKey, observation: HistoryMetadataObservation) {
        if (!canAddUri(key.url)) {
            logger.debug("Not recording metadata (canAddUri=false) for: ${key.url}")
            return
        }
        withContext(writeScope.coroutineContext) {
            handlePlacesExceptions("noteHistoryMetadataObservation") {
                places.writer().noteHistoryMetadataObservation(observation.into(key), observation.options())
            }
        }
    }

    override suspend fun deleteHistoryMetadataOlderThan(olderThan: Long) {
        withContext(writeScope.coroutineContext) {
            handlePlacesExceptions("deleteHistoryMetadataOlderThan") { places.writer().deleteHistoryMetadataOlderThan(olderThan) }
        }
    }

    override suspend fun deleteHistoryMetadata(key: HistoryMetadataKey) {
        withContext(writeScope.coroutineContext) {
            handlePlacesExceptions("deleteHistoryMetadata") { places.writer().deleteHistoryMetadata(key.into()) }
        }
    }

    override suspend fun deleteHistoryMetadata(searchTerm: String) {
        deleteHistoryMetadata { it.searchTerm == searchTerm.lowercase() }
    }

    override suspend fun deleteHistoryMetadataForUrl(url: String) {
        deleteHistoryMetadata { it.url == url }
    }

    private suspend fun deleteHistoryMetadata(predicate: (mozilla.appservices.places.uniffi.HistoryMetadata) -> Boolean) {
        withContext(writeScope.coroutineContext) {
            handlePlacesExceptions("deleteHistoryMetadata") {
                places.reader().getHistoryMetadataSince(Long.MIN_VALUE).filter(predicate).forEach {
                    places.writer().deleteHistoryMetadata(HistoryMetadataKey(url = it.url, searchTerm = it.searchTerm, referrerUrl = it.referrerUrl).into())
                }
            }
        }
    }

    override fun canAddUri(uri: String): Boolean {
        val parsedUri = uri.toUri()
        val scheme = parsedUri.normalizeScheme().scheme ?: return false
        if (scheme == "http" || scheme == "https") return true
        if (uri.startsWith("about:reader")) return true
        val schemasToIgnore = listOf("", "about", "imap", "news", "mailbox", "moz-anno", "moz-extension", "view-source", "chrome", "resource", "data", "javascript", "blob")
        return !schemasToIgnore.contains(scheme)
    }
}
