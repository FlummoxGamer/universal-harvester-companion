package app.harvester.companion.core.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import app.harvester.companion.UHLog
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.feature.downloads.filewriter.DownloadFileWriter

class UhDownloadFileWriter(private val context: Context) : DownloadFileWriter {

    private data class ExistingMediaEntry(
        val uri: Uri,
        val sizeBytes: Long,
        val isPending: Boolean,
    )

    override fun useFileStream(
        download: DownloadState,
        append: Boolean,
        shouldUseScopedStorage: Boolean,
        onUpdateState: (DownloadState) -> Unit,
        block: (OutputStream) -> Unit,
    ) {
        if (download.directoryPath.startsWith("content://")) {
            val wroteSaf = useSafTreeStream(download, append, onUpdateState, block)
            if (wroteSaf) return
            UHLog.w(
                "download_warn",
                "Falling back to default download writer because SAF tree URI could not be opened: ${download.directoryPath}"
            )
        }

        if (shouldUseScopedStorage) {
            useScopedStorageStream(download, append, onUpdateState, block)
        } else {
            useLegacyStorageStream(download, append, onUpdateState, block)
        }
    }

    private fun useSafTreeStream(
        download: DownloadState,
        append: Boolean,
        onUpdateState: (DownloadState) -> Unit,
        block: (OutputStream) -> Unit,
    ): Boolean {
        return try {
            val treeUri = Uri.parse(download.directoryPath)
            val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
            if (treeDoc == null || !treeDoc.canWrite()) {
                UHLog.w("download_warn", "SAF download directory not writable: ${download.directoryPath}")
                return false
            }
            val fileName = download.fileName ?: "download"
            val mimeType = download.contentType ?: "application/octet-stream"
            val existing = treeDoc.findFile(fileName)
            val createdNew: Boolean
            val targetDoc = if (existing != null) {
                createdNew = false
                existing
            } else {
                createdNew = true
                treeDoc.createFile(mimeType, fileName)
            }
            if (targetDoc == null) {
                UHLog.e("download_error", "Failed to create SAF document for $fileName in ${download.directoryPath}")
                return false
            }
            val resolvedName = targetDoc.name
            if (createdNew && !resolvedName.isNullOrBlank() && resolvedName != download.fileName) {
                onUpdateState(download.copy(fileName = resolvedName))
            }
            val mode = if (append) "wa" else "wt"
            val out = context.contentResolver.openOutputStream(targetDoc.uri, mode)
                ?: if (!append) context.contentResolver.openOutputStream(targetDoc.uri, "w") else null
            if (out == null) {
                UHLog.e("download_error", "ContentResolver returned null stream for ${targetDoc.uri}")
                return false
            }
            out.use { outStream ->
                block(outStream)
            }
            true
        } catch (e: Exception) {
            UHLog.throwable("download_error", "Failed writing download to SAF tree URI", e)
            false
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
        val fallbackDir = DownloadLocationResolver.defaultPublicDownloadsPath()
        val effectiveDir = if (download.directoryPath.startsWith("content://") || download.directoryPath.isBlank()) {
            fallbackDir
        } else {
            download.directoryPath
        }
        val relativePath = effectiveDir
            .removePrefix("/storage/emulated/0/")
            .trimEnd('/')
            .ifBlank { Environment.DIRECTORY_DOWNLOADS }

        val existing = findExistingMediaEntry(collection, fileName, relativePath)
        var uri: Uri? = null
        var uriNewlyInserted = false
        var effectiveAppend = append

        if (existing != null) {
            when {
                // Range resume: append directly into the partial / pending file.
                append -> {
                    uri = existing.uri
                }
                // Retry / resume where server didn't send 206, OR a previous 0-byte pending row was left over:
                // reuse and overwrite in-place so MediaStore never creates "file (1)(1).ext".
                existing.isPending || existing.sizeBytes == 0L || download.currentBytesCopied > 0L -> {
                    uri = existing.uri
                    effectiveAppend = false
                }
                else -> {
                    // Clean up any orphaned 0-byte pending rows before letting MediaStore allocate a new file name.
                    cleanupZeroBytePendingEntries(collection, fileName, relativePath)
                }
            }
        }

        if (uri == null) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativePath/")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            uri = resolver.insert(collection, values)
            uriNewlyInserted = uri != null
            effectiveAppend = false
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val pendingValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                resolver.update(uri, pendingValues, null, null)
            } catch (_: Exception) {}
        }

        if (uri == null) {
            UHLog.e("download_error", "Failed to open MediaStore entry for $fileName")
            return
        }

        if (uriNewlyInserted) {
            try {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val actualName = c.getString(0)
                        if (!actualName.isNullOrBlank() && actualName != download.fileName) {
                            onUpdateState(download.copy(fileName = actualName))
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        try {
            val mode = if (effectiveAppend) "wa" else "rwt"
            val stream = try {
                resolver.openOutputStream(uri, mode)
            } catch (_: Exception) {
                resolver.openOutputStream(uri, if (effectiveAppend) "wa" else "w")
            }
            if (stream == null) {
                UHLog.e("download_error", "Failed to open MediaStore output stream for $uri")
                return
            }
            stream.use { outStream ->
                block(outStream)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
        } catch (e: Exception) {
            // Leave IS_PENDING=1 and partial bytes untouched when interrupted so Range resume continues seamlessly.
            UHLog.throwable("download_error", "Scoped storage download write interrupted for $fileName", e)
            throw e
        }
    }

    private fun useLegacyStorageStream(
        download: DownloadState,
        append: Boolean,
        onUpdateState: (DownloadState) -> Unit,
        block: (OutputStream) -> Unit,
    ) {
        val dirPath = if (download.directoryPath.startsWith("content://") || download.directoryPath.isBlank()) {
            DownloadLocationResolver.defaultPublicDownloadsPath()
        } else {
            download.directoryPath
        }
        val directory = File(dirPath)
        if (!directory.exists() && !directory.mkdirs()) {
            UHLog.e("download_error", "Failed to create download directory: $dirPath")
        }

        val file = File(directory, download.fileName ?: "download")
        FileOutputStream(file, append).use { outStream ->
            block(outStream)
        }
    }

    private fun findExistingMediaEntry(
        collection: Uri,
        fileName: String,
        relativePath: String,
    ): ExistingMediaEntry? {
        val relNoSlash = relativePath.trimEnd('/')
        val relWithSlash = "$relNoSlash/"
        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.IS_PENDING,
            )
        } else {
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.SIZE,
            )
        }
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
            "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?)"
        val selectionArgs = arrayOf(fileName, relNoSlash, relWithSlash)

        queryMediaEntries(collection, projection, selection, selectionArgs)?.let { return it }

        // Fallback to name-only match (including pending rows) in case folder string differed slightly.
        val nameOnlySelection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        return queryMediaEntries(collection, projection, nameOnlySelection, arrayOf(fileName))
    }

    private fun queryMediaEntries(
        collection: Uri,
        projection: Array<String>,
        selection: String,
        selectionArgs: Array<String>,
    ): ExistingMediaEntry? {
        try {
            val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val queryArgs = Bundle().apply {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                    putString(
                        android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                        "${MediaStore.MediaColumns._ID} DESC"
                    )
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                }
                context.contentResolver.query(collection, projection, queryArgs, null)
            } else {
                context.contentResolver.query(
                    collection,
                    projection,
                    selection,
                    selectionArgs,
                    "${MediaStore.MediaColumns._ID} DESC"
                )
            }
            cursor?.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val sizeIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val pendingIdx = c.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                // Prefer any matching pending row first so partial transfers always resume into the pending slot.
                var fallbackEntry: ExistingMediaEntry? = null
                while (c.moveToNext()) {
                    val id = if (idIdx >= 0) c.getLong(idIdx) else continue
                    val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                    val isPending = if (pendingIdx >= 0) c.getInt(pendingIdx) == 1 else false
                    val entry = ExistingMediaEntry(
                        uri = Uri.withAppendedPath(collection, id.toString()),
                        sizeBytes = size,
                        isPending = isPending,
                    )
                    if (isPending) return entry
                    if (fallbackEntry == null) fallbackEntry = entry
                }
                return fallbackEntry
            }
        } catch (_: Exception) {}
        return null
    }

    private fun cleanupZeroBytePendingEntries(
        collection: Uri,
        fileName: String,
        relativePath: String,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val relNoSlash = relativePath.trimEnd('/')
        val relWithSlash = "$relNoSlash/"
        try {
            val queryArgs = Bundle().apply {
                putString(
                    android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 1 AND " +
                        "${MediaStore.MediaColumns.SIZE} = 0 AND (${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?)"
                )
                putStringArray(
                    android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    arrayOf(fileName, relNoSlash, relWithSlash)
                )
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                queryArgs,
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    context.contentResolver.delete(Uri.withAppendedPath(collection, id.toString()), null, null)
                }
            }
        } catch (_: Exception) {}
    }
}
