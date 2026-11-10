package app.harvester.companion.data.downloads.ui

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
import app.harvester.companion.data.downloads.UhDownloadUIState
import app.harvester.companion.data.downloads.UhDownloadUIStore
import app.harvester.companion.data.downloads.UhFileItem
import app.harvester.companion.data.downloads.di.UhDownloadUIMiddlewareProvider

class UhDownloadsFragment : Fragment() {

    private val downloadStore by fragmentStore(UhDownloadUIState.INITIAL) {
        UhDownloadUIStore(
            initialState = it,
            middleware = UhDownloadUIMiddlewareProvider.provideMiddleware(
                coroutineScope = viewLifecycleOwner.lifecycleScope,
                applicationContext = requireContext().applicationContext,
                navController = findNavController(),
            ),
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = content {
        UhDownloadsScreen(
            downloadsStore = downloadStore,
            onItemClick = { openItem(it) },
            onSettingsClick = { /* Implement navigation to settings */ },
            onBackClick = { findNavController().popBackStack() }
        )
    }

    private fun openItem(item: UhFileItem) {
        context?.let {
            val fileUtils = DefaultDownloadFileUtils(requireContext()) {
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).path
            }
            val canOpenFile = fileUtils.openFile(
                fileName = item.fileName,
                directoryPath = item.directoryPath,
                contentType = item.contentType,
            )
            if (!canOpenFile) {
                Toast.makeText(it, "Cannot open this file", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
