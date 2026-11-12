package app.harvester.companion.settings.downloads

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

class DefaultAndroidFileUtils(private val context: Context) : AndroidFileUtils {
    override val externalStorageDirectory: File
        get() = Environment.getExternalStorageDirectory()

    override val externalStoragePublicDownloadsDirectory: File
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    override fun isTreeUri(uri: Uri): Boolean = DocumentsContract.isTreeUri(uri)

    override fun getTreeDocumentId(uri: Uri): String? = DocumentsContract.getTreeDocumentId(uri)

    override fun getTreeUriName(uri: Uri): String? = DocumentFile.fromTreeUri(context, uri)?.name

    override fun hasUriPermission(uri: Uri): Boolean {
        val persistedPermissions = context.contentResolver.persistedUriPermissions
        return persistedPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
    }

    override fun getExternalStorageVolumeName(volumeId: String): String? {
        val storageManager = context.getSystemService(StorageManager::class.java)
        return storageManager.storageVolumes
            .firstOrNull { it.uuid?.equals(volumeId, ignoreCase = true) == true }
            ?.getDescription(context)
    }
}
