package app.harvester.companion.data.downloads.middleware

import androidx.annotation.FloatRange
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.DownloadAction
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.utils.DateTimeProvider
import mozilla.components.support.utils.DefaultDateTimeProvider
import app.harvester.companion.data.downloads.UhDownloadUIAction
import app.harvester.companion.data.downloads.UhDownloadUIState
import app.harvester.companion.data.downloads.UhFileItem
import app.harvester.companion.data.downloads.TimeCategory

class UhDownloadUIMapperMiddleware(
    private val browserStore: BrowserStore,
    private val fileItemDescriptionProvider: UhFileItemDescriptionProvider,
    private val scope: CoroutineScope,
    private val dateTimeProvider: DateTimeProvider = DefaultDateTimeProvider(),
) : Middleware<UhDownloadUIState, UhDownloadUIAction> {

    override fun invoke(
        store: Store<UhDownloadUIState, UhDownloadUIAction>,
        next: (UhDownloadUIAction) -> Unit,
        action: UhDownloadUIAction,
    ) {
        next(action)
        when (action) {
            is UhDownloadUIAction.Init -> {
                browserStore.dispatch(DownloadAction.RemoveDeletedDownloads)
                update(store)
            }
            else -> {}
        }
    }

    private fun update(store: Store<UhDownloadUIState, UhDownloadUIAction>) {
        scope.launch {
            browserStore.flow()
                .distinctUntilChangedBy { it.downloads }
                .map { it.downloads.toFileItemsList() }
                .collect { store.dispatch(UhDownloadUIAction.UpdateFileItems(it)) }
        }
    }

    private suspend fun Map<String, DownloadState>.toFileItemsList(): List<UhFileItem> =
        values
            .filter { isDisplayableItem(it.status) }
            .sortedByDescending { it.createdTime }
            .distinctBy { Triple(it.fileName, it.status, it.directoryPath) }
            .map { it.toFileItem() }

    private fun isDisplayableItem(status: DownloadState.Status) = status != DownloadState.Status.CANCELLED

    private suspend fun DownloadState.toFileItem() =
        UhFileItem(
            id = id,
            url = url,
            fileName = fileName,
            filePath = filePath,
            directoryPath = directoryPath,
            displayedShortUrl = try { android.net.Uri.parse(url).host ?: "" } catch (e: Exception) { "" },
            contentType = contentType,
            status = status.toFileItemStatus(progress = progress),
            timeCategory = categorizeGroup(epochMillis = createdTime, status = status),
            description = fileItemDescriptionProvider.getDescription(downloadState = this),
        )

    private fun DownloadState.Status.toFileItemStatus(
        @FloatRange(from = 0.0, to = 1.0) progress: Float?
    ): UhFileItem.Status =
        when (this) {
            DownloadState.Status.INITIATED -> UhFileItem.Status.Initiated
            DownloadState.Status.DOWNLOADING -> UhFileItem.Status.Downloading(progress = progress)
            DownloadState.Status.PAUSED -> UhFileItem.Status.Paused(progress = progress)
            DownloadState.Status.CANCELLED -> UhFileItem.Status.Cancelled
            DownloadState.Status.FAILED -> UhFileItem.Status.Failed
            DownloadState.Status.COMPLETED -> UhFileItem.Status.Completed
        }

    private fun categorizeGroup(epochMillis: Long, status: DownloadState.Status): TimeCategory {
        if (isDisplayableItem(status) && status != DownloadState.Status.COMPLETED) {
            return TimeCategory.IN_PROGRESS
        }

        val currentDate = dateTimeProvider.currentLocalDate()
        val inputDate = Instant.ofEpochMilli(epochMillis).atZone(dateTimeProvider.currentZoneId()).toLocalDate()

        return when {
            inputDate.isEqual(currentDate) -> TimeCategory.TODAY
            inputDate.isEqual(currentDate.minusDays(1)) -> TimeCategory.YESTERDAY
            inputDate.isAfter(currentDate.minusDays(NUM_DAYS_IN_LAST_7_DAYS_PERIOD)) -> TimeCategory.LAST_7_DAYS
            inputDate.isAfter(currentDate.minusDays(NUM_DAYS_IN_LAST_30_DAYS_PERIOD)) -> TimeCategory.LAST_30_DAYS
            else -> TimeCategory.OLDER
        }
    }

    companion object {
        private const val NUM_DAYS_IN_LAST_7_DAYS_PERIOD = 7L
        private const val NUM_DAYS_IN_LAST_30_DAYS_PERIOD = 30L
    }
}
