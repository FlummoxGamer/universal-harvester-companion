package app.harvester.companion.data.downloads.middleware

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mozilla.components.feature.downloads.DownloadsUseCases
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import app.harvester.companion.data.downloads.UhDownloadUIAction
import app.harvester.companion.data.downloads.UhDownloadUIState
import app.harvester.companion.data.downloads.UhFileItem

enum class UhDeleteDownloadBehavior {
    ASK_WHEN_DELETING,
    DELETE_FROM_DEVICE,
    DELETE_FROM_HISTORY
}

class UhDownloadDeleteMiddleware(
    private val undoDelay: Long = 5000L,
    private val removeDownloadUseCase: DownloadsUseCases.RemoveDownloadUseCase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val deleteBehaviorProvider: () -> UhDeleteDownloadBehavior,
) : Middleware<UhDownloadUIState, UhDownloadUIAction> {
    private var lastDeleteOperation: DeleteOperation? = null
    private val coroutineScope = CoroutineScope(dispatcher)

    override fun invoke(
        store: Store<UhDownloadUIState, UhDownloadUIAction>,
        next: (UhDownloadUIAction) -> Unit,
        action: UhDownloadUIAction,
    ) {
        next(action)
        when (action) {
            is UhDownloadUIAction.RequestDeleteMultiple -> handleCompletedDownloadDeleteRequest(store, action.items)
            is UhDownloadUIAction.RequestDelete -> {
                when (action.item.status) {
                    is UhFileItem.Status.Completed -> handleCompletedDownloadDeleteRequest(store, setOf(action.item))
                    else -> removeDownload(store, action.item.id, removeFromDisk = true)
                }
            }
            is UhDownloadUIAction.ConfirmMultiSelectDelete -> {
                val removeFromDisk = deleteBehaviorProvider() == UhDeleteDownloadBehavior.DELETE_FROM_DEVICE
                store.dispatch(UhDownloadUIAction.AddPendingDeletionSet(removeFromDisk = removeFromDisk, items = action.items))
            }
            is UhDownloadUIAction.AddPendingDeletionSet -> startDelayedRemoval(store, action.items, action.removeFromDisk, undoDelay)
            is UhDownloadUIAction.UndoPendingDeletion -> lastDeleteOperation?.cancel()
            else -> {}
        }
    }

    private fun handleCompletedDownloadDeleteRequest(
        store: Store<UhDownloadUIState, UhDownloadUIAction>,
        items: Set<UhFileItem>,
    ) {
        val deleteBehavior = deleteBehaviorProvider()
        if (deleteBehavior == UhDeleteDownloadBehavior.ASK_WHEN_DELETING) {
            store.dispatch(UhDownloadUIAction.ShowDeleteDialog(items))
        } else {
            if (items.size > 1) {
                store.dispatch(UhDownloadUIAction.ShowMultiSelectDeleteDialog(items))
            } else {
                val removeFromDisk = deleteBehavior == UhDeleteDownloadBehavior.DELETE_FROM_DEVICE
                store.dispatch(UhDownloadUIAction.AddPendingDeletionSet(removeFromDisk = removeFromDisk, items = items))
            }
        }
    }

    private fun startDelayedRemoval(
        store: Store<UhDownloadUIState, UhDownloadUIAction>,
        items: Set<UhFileItem>,
        removeFromDisk: Boolean,
        delay: Long,
    ) {
        val itemIds = items.map { it.id }.toSet()
        val job = coroutineScope.launch {
            try {
                delay(delay)
                itemIds.forEach { removeDownload(store, it, removeFromDisk) }
                store.dispatch(UhDownloadUIAction.FileItemDeletedSuccessfully)
            } catch (e: CancellationException) {
                store.dispatch(UhDownloadUIAction.UndoPendingDeletionSet(itemIds))
            } finally {
                if (lastDeleteOperation?.items == itemIds) lastDeleteOperation = null
            }
        }
        lastDeleteOperation = DeleteOperation(job, itemIds)
    }

    private fun removeDownload(
        store: Store<UhDownloadUIState, UhDownloadUIAction>,
        id: String,
        removeFromDisk: Boolean,
    ) {
        removeDownloadUseCase(id, removeFromDisk)
        store.dispatch(UhDownloadUIAction.CancelDownload(id))
    }

    private data class DeleteOperation(
        private val deleteJob: Job,
        val items: Set<String>,
    ) {
        fun cancel() {
            deleteJob.cancel(CancellationException("Undo deletion"))
        }
    }
}
