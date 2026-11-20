package app.harvester.companion.data.downloads.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import mozilla.components.lib.state.helpers.StoreProvider.Companion.fragmentStore
import mozilla.components.support.utils.DefaultDownloadFileUtils
import app.harvester.companion.R
import app.harvester.companion.UHLog
import app.harvester.companion.core.downloads.DownloadLocationResolver
import app.harvester.companion.data.downloads.UhDownloadUIState
import app.harvester.companion.data.downloads.UhDownloadUIStore
import app.harvester.companion.data.downloads.UhFileItem
import app.harvester.companion.data.downloads.di.UhDownloadUIMiddlewareProvider

class UhDownloadsFragment : Fragment() {

    private val downloadStore by fragmentStore(UhDownloadUIState.INITIAL) {
        UhDownloadUIStore(
            initialState = it,
            middleware = UhDownloadUIMiddlewareProvider.provideMiddleware(
                coroutineScope = lifecycleScope,
                applicationContext = requireContext().applicationContext,
                navControllerProvider = {
                    if (isAdded) {
                        try {
                            findNavController()
                        } catch (e: Exception) {
                            UHLog.w("ui_warn", "NavController unavailable in UhDownloadsFragment: ${e.message}")
                            null
                        }
                    } else null
                },
            ),
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = content {
        UhDownloadScreen(
            downloadsStore = downloadStore,
            onItemClick = { openItem(it) },
            onSettingsClick = {
                try {
                    if (isAdded) {
                        findNavController().navigate(R.id.action_downloadsFragment_to_settingsFragment)
                    }
                } catch (e: Exception) {
                    UHLog.throwable("ui_error", "Failed navigating to download settings", e)
                }
            },
            onBackClick = {
                try {
                    if (isAdded && !findNavController().popBackStack()) {
                        activity?.onBackPressedDispatcher?.onBackPressed()
                    }
                } catch (e: Exception) {
                    UHLog.throwable("ui_error", "Failed navigating back from downloads", e)
                }
            }
        )
    }

    private fun openItem(item: UhFileItem) {
        val ctx = context ?: return
        if (item.directoryPath.startsWith("content://")) {
            val safUri = DownloadLocationResolver.findSafFileUri(ctx, item.directoryPath, item.fileName)
            if (safUri != null) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(safUri, item.contentType ?: "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(intent, item.fileName ?: "Open file"))
                    return
                } catch (e: Exception) {
                    UHLog.throwable("download_error", "Failed opening SAF download item ${item.fileName}", e)
                }
            } else {
                UHLog.w("download_warn", "Could not locate SAF download item: ${item.fileName}")
            }
        }

        val fileUtils = DefaultDownloadFileUtils(ctx) {
            DownloadLocationResolver.resolveTargetDirectory(ctx.applicationContext)
        }
        val canOpenFile = fileUtils.openFile(
            fileName = item.fileName,
            directoryPath = item.directoryPath,
            contentType = item.contentType,
        )
        if (!canOpenFile) {
            UHLog.w("download_warn", "Unable to open downloaded file: ${item.fileName}")
            Toast.makeText(ctx, "Cannot open this file", Toast.LENGTH_SHORT).show()
        }
    }
}
