package app.harvester.companion.feature.session

import mozilla.components.concept.engine.history.HistoryTrackingDelegate
import mozilla.components.concept.storage.HistoryStorage
import mozilla.components.concept.storage.PageObservation
import mozilla.components.concept.storage.PageVisit
import mozilla.components.support.ktx.kotlin.tryGetHostFromUrl

class HistoryDelegate(private val historyStorage: Lazy<HistoryStorage>) : HistoryTrackingDelegate {
    override suspend fun onVisited(uri: String, visit: PageVisit) {
        historyStorage.value.recordVisit(uri, visit)
    }

    override suspend fun onTitleChanged(uri: String, title: String) {
        historyStorage.value.recordObservation(uri, PageObservation(title = title))
    }

    override suspend fun onPreviewImageChange(uri: String, previewImageUrl: String) {
        historyStorage.value.recordObservation(
            uri,
            PageObservation(previewImageUrl = previewImageUrl),
        )
    }

    override suspend fun getVisited(uris: List<String>): List<Boolean> {
        return historyStorage.value.getVisited(uris)
    }

    override suspend fun getVisited(): List<String> {
        return historyStorage.value.getVisited()
    }

    override fun shouldStoreUri(uri: String) = historyStorage.value.canAddUri(uri)
}

private fun String.hostMatchesDomain(domain: String): Boolean {
    val host = tryGetHostFromUrl()
    return host == domain || host.endsWith(".$domain")
}
