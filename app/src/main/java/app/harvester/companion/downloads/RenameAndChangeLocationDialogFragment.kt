package app.harvester.companion.downloads

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import app.harvester.companion.R
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.core.downloads.DownloadLocationResolver
import app.harvester.companion.settings.downloads.DefaultAndroidFileUtils
import app.harvester.companion.settings.downloads.DefaultDownloadLocationFormatter
import app.harvester.companion.theme.UHTheme
import mozilla.components.support.ktx.util.PromptAbuserDetector
import mozilla.components.support.utils.OnEnterAnimationCompleteListener

class RenameAndChangeLocationDialogFragment : DialogFragment(), OnEnterAnimationCompleteListener {
    private val safeArguments get() = requireNotNull(arguments)
    private val promptAbuserDetector = PromptAbuserDetector(TIME_SHOWN_OFFSET_MILLIS)

    internal val fileName: String get() = safeArguments.getString(KEY_FILE_NAME, "")
    internal val directoryPath: String get() = safeArguments.getString(KEY_DIRECTORY_PATH, "")
    internal val contentSize: Long get() = safeArguments.getLong(KEY_CONTENT_SIZE, 0)

    private var dialogState by mutableStateOf(RenameAndChangeLocationDialogState(fileName = "", directoryPath = ""))
    private var isActionHandled = false
    private lateinit var downloadLocationFormatter: DefaultDownloadLocationFormatter

    var onConfirmSave: (String, String, Boolean) -> Unit = { _, _, _ -> }
    var onCancel: () -> Unit = {}

    internal var directoryLauncher: ActivityResultLauncher<Uri?> =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val safeUri = uri ?: return@registerForActivityResult
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context?.applicationContext?.contentResolver?.takePersistableUriPermission(safeUri, flags)
                dialogState = dialogState.copy(directoryPath = safeUri.toString())
            } catch (e: SecurityException) {
                UHLog.throwable("download_error", "Failed to take persistable URI permission in download prompt", e)
                dialogState = dialogState.copy(directoryPath = safeUri.toString())
            }
        }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setBackgroundDrawableResource(android.R.color.transparent)
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val maxDp = if (isLandscape) 420f else 348f
            val widthRatio = if (isLandscape) 0.72f else 0.90f
            val widthPx = (resources.displayMetrics.widthPixels * widthRatio)
                .toInt()
                .coerceAtMost((maxDp * resources.displayMetrics.density).toInt())
            setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onResume() {
        super.onResume()
        promptAbuserDetector.start()
    }

    override fun onEnterAnimationComplete() {
        promptAbuserDetector.start()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        if (!isActionHandled) {
            isActionHandled = true
            onCancel()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        downloadLocationFormatter = DefaultDownloadLocationFormatter(DefaultAndroidFileUtils(ctx))
        val initialDir = directoryPath.ifBlank { DownloadLocationResolver.resolveTargetDirectory(ctx) }
        dialogState = RenameAndChangeLocationDialogState(
            fileName = fileName,
            directoryPath = initialDir,
            rememberAsDefault = false,
        )
        promptAbuserDetector.start()

        return Dialog(ctx).apply {
            setCanceledOnTouchOutside(true)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                UHTheme {
                    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                    val friendlyPath = try {
                        downloadLocationFormatter.getFriendlyPath(dialogState.directoryPath)
                    } catch (_: Exception) {
                        if (dialogState.directoryPath.startsWith("content://")) {
                            val seg = Uri.parse(dialogState.directoryPath).lastPathSegment?.substringAfterLast(':')
                            if (!seg.isNullOrBlank()) seg else "Selected folder"
                        } else {
                            dialogState.directoryPath.substringAfterLast('/').ifBlank { "Downloads" }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = if (isLandscape) 420.dp else 348.dp)
                    ) {
                        RenameAndChangeLocationDialogContent(
                            dialogState = dialogState,
                            friendlyPath = friendlyPath,
                            title = buildDialogTitle(),
                            onFileNameChange = { newFileName -> dialogState = dialogState.copy(fileName = newFileName) },
                            onRememberChange = { remember -> dialogState = dialogState.copy(rememberAsDefault = remember) },
                            onDirectorySelect = {
                                try {
                                    directoryLauncher.launch(null)
                                } catch (e: ActivityNotFoundException) {
                                    UHLog.throwable("download_error", "No folder picker available", e)
                                    Toast.makeText(
                                        ctx,
                                        R.string.preferences_downloads_no_folder_picker_available,
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                            onConfirm = {
                                if (isActionHandled) return@RenameAndChangeLocationDialogContent
                                isActionHandled = true
                                val finalName = dialogState.fileName
                                val finalDir = dialogState.directoryPath
                                val rememberDefault = dialogState.rememberAsDefault
                                onConfirmSave(finalName, finalDir, rememberDefault)
                                try {
                                    dismissAllowingStateLoss()
                                } catch (_: Exception) {}
                            },
                            onCancel = {
                                if (isActionHandled) return@RenameAndChangeLocationDialogContent
                                isActionHandled = true
                                onCancel()
                                try {
                                    dismissAllowingStateLoss()
                                } catch (_: Exception) {}
                            },
                        )
                    }
                }
            }
        }
    }

    private fun buildDialogTitle(): String {
        return if (contentSize > 0L) {
            val contentSizeInBytes = (requireContext().applicationContext as UHApp).components.fileSizeFormatter.formatSizeInBytes(contentSize)
            getString(R.string.download_rename_and_change_location_dialog_title, contentSizeInBytes)
        } else {
            getString(R.string.download_rename_and_change_location_dialog_title_with_unknown_size)
        }
    }

    companion object {
        private const val KEY_FILE_NAME = "file_name"
        private const val KEY_DIRECTORY_PATH = "directory_path"
        private const val KEY_CONTENT_SIZE = "content_size"
        const val RENAME_AND_CHANGE_LOCATION_DIALOG_TAG = "RENAME_AND_CHANGE_LOCATION_DIALOG_TAG"
        private const val TIME_SHOWN_OFFSET_MILLIS = 500

        fun newInstance(fileName: String, directoryPath: String, contentSize: Long) =
            RenameAndChangeLocationDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(KEY_FILE_NAME, fileName)
                    putString(KEY_DIRECTORY_PATH, directoryPath)
                    putLong(KEY_CONTENT_SIZE, contentSize)
                }
            }
    }
}

private fun PromptAbuserDetector.start() {
    resetJSAlertAbuseState()
    updateJSDialogAbusedState()
}
