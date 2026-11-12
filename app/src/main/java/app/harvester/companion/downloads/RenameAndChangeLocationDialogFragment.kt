package app.harvester.companion.downloads

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.WindowManager
import android.widget.Toast
import app.harvester.companion.R
import app.harvester.companion.settings.downloads.DefaultAndroidFileUtils
import app.harvester.companion.settings.downloads.DefaultDownloadLocationFormatter
import app.harvester.companion.settings.downloads.MissingUriPermission
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.ktx.util.PromptAbuserDetector
import mozilla.components.support.utils.OnEnterAnimationCompleteListener
import app.harvester.companion.R
import app.harvester.companion.UHApp
import app.harvester.companion.settings.downloads.DefaultAndroidFileUtils
import app.harvester.companion.settings.downloads.DefaultDownloadLocationFormatter
import app.harvester.companion.settings.downloads.MissingUriPermission
import app.harvester.companion.theme.UHTheme

class RenameAndChangeLocationDialogFragment : DialogFragment(), OnEnterAnimationCompleteListener {
    private val logger = Logger("RenameAndChangeLocationDialogFragment")
    private val safeArguments get() = requireNotNull(arguments)
    private val promptAbuserDetector = PromptAbuserDetector(TIME_SHOWN_OFFSET_MILLIS)

    internal val fileName: String get() = safeArguments.getString(KEY_FILE_NAME, "")
    internal val directoryPath: String get() = safeArguments.getString(KEY_DIRECTORY_PATH, "")
    internal val contentSize: Long get() = safeArguments.getLong(KEY_CONTENT_SIZE, 0)

    private var dialogState by mutableStateOf(RenameAndChangeLocationDialogState(fileName = "", directoryPath = ""))
    private lateinit var downloadLocationFormatter: DefaultDownloadLocationFormatter

    var onConfirmSave: (String, String) -> Unit = { _, _ -> }
    var onCancel: () -> Unit = {}

    internal var directoryLauncher: ActivityResultLauncher<Uri?> =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val safeUri = uri ?: return@registerForActivityResult
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context?.contentResolver?.takePersistableUriPermission(safeUri, flags)
                dialogState = dialogState.copy(directoryPath = safeUri.toString())
            } catch (e: SecurityException) {
                logger.error("Failed to take persistable URI permission.", e)
            }
        }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply { clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM) }
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
        onCancel()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        downloadLocationFormatter = DefaultDownloadLocationFormatter(DefaultAndroidFileUtils(requireContext()))
        dialogState = RenameAndChangeLocationDialogState(fileName = fileName, directoryPath = directoryPath)
        promptAbuserDetector.start()

        val composeView = ComposeView(requireContext()).apply {
            setContent {
                UHTheme {
                    val friendlyPath = try {
                        downloadLocationFormatter.getFriendlyPath(dialogState.directoryPath)
                    } catch (e: MissingUriPermission) {
                        val defaultLocation = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
                        downloadLocationFormatter.getFriendlyPath(defaultLocation)
                    }

                    RenameAndChangeLocationDialogContent(
                        dialogState = dialogState,
                        friendlyPath = friendlyPath,
                        title = buildDialogTitle(),
                        onFileNameChange = { newFileName -> dialogState = dialogState.copy(fileName = newFileName) },
                        onDirectorySelect = {
                            try {
                                directoryLauncher.launch(null)
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(requireContext(), R.string.preferences_downloads_no_folder_picker_available, Toast.LENGTH_LONG).show()
                            }
                        },
                        onConfirm = {
                            onConfirmSave(dialogState.fileName, dialogState.directoryPath)
                            dismiss()
                        },
                        onCancel = {
                            onCancel()
                            dismiss()
                        },
                    )
                }
            }
        }

        return MaterialAlertDialogBuilder(requireContext()).setView(composeView).create()
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
