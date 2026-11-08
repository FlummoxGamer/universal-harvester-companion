package app.harvester.companion.components.appstate.snackbar

import com.google.android.material.snackbar.BaseTransientBottomBar.LENGTH_SHORT
import mozilla.components.browser.state.state.content.DownloadState

sealed class SnackbarState {
    data class None(val previous: SnackbarState? = null) : SnackbarState()

    data class ShowSnackbar(
        val title: String,
        val duration: Int = LENGTH_SHORT,
    ) : SnackbarState()

    data class Dismiss(val previous: SnackbarState? = null) : SnackbarState()

    data class DownloadCompleted(val downloadState: DownloadState) : SnackbarState()
    data class DownloadFailed(val fileName: String?) : SnackbarState()
    data class DownloadInProgress(val downloadId: String) : SnackbarState()
    data class CannotOpenFileError(val downloadState: DownloadState) : SnackbarState()
}
