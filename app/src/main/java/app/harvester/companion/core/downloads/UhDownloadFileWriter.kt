package app.harvester.companion.core.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.feature.downloads.filewriter.DownloadFileWriter

class UhDownloadFileWriter(private val context: Context) : DownloadFileWriter {

    override fun useFileStream(
        download: DownloadState,
        append: Boolean,
        shouldUseScopedStorage: Boolean,
        onUpdateState: (DownloadState) -> Unit,
        block: (OutputStream) -> Unit,
    ) {
        if (shouldUseScopedStorage) {
            useScopedStorageStream(download, append, onUpdateState, block)
        } else {
            useLegacyStorageStream(download, append, onUpdateState, block)
        }
    }

    private fun useScopedStorageStream(
        download: DownloadState,
        append: Boolean,
        onUpdateState: (DownloadState) -> Unit,
        block: (OutputStream) -> Unit,
    ) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val mimeType = download.contentType ?: "application/octet-stream"
        val fileName = download.fileName ?: "download"
        
        var uri = download.filePath.takeIf { it.startsWith("content://") }?.let { Uri.parse(it) }
        
        if (uri == null) {
            val existingUri = findExistingFileUri(collection, fileName, download.directoryPath)
            if (existingUri != null && append) {
                uri = existingUri
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, download.directoryPath.replace("/storage/emulated/0/", ""))
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                uri = resolver.insert(collection, values)
            }
        }

        uri?.let { targetUri ->
            onUpdateState(download.copy(filePath = targetUri.toString()))
            val mode = if (append) "wa" else "w"
            resolver.openOutputStream(targetUri, mode)?.use { outStream ->
                block(outStream)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(targetUri, done, null, null)
            }
        }
    }

    private fun useLegacyStorageStream(
        download: DownloadState,
        append: Boolean,
        onUpdateState: (DownloadState) -> Unit,
        block: (OutputStream) -> Unit,
    ) {
        val directory = File(download.directoryPath)
        if (!directory.exists()) directory.mkdirs()
        
        val file = File(directory, download.fileName ?: "download")
        onUpdateState(download.copy(filePath = file.absolutePath))
        
        FileOutputStream(file, append).use { outStream ->
            block(outStream)
        }
    }

    private fun findExistingFileUri(collection: Uri, fileName: String, directoryPath: String): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(fileName, "%${directoryPath.substringAfterLast("/")}%")
        
        context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }
}
