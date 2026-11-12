package app.harvester.companion.settings.downloads

import android.net.Uri
import java.io.File

interface AndroidFileUtils {
    val externalStorageDirectory: File
    val externalStoragePublicDownloadsDirectory: File
    fun isTreeUri(uri: Uri): Boolean
    fun getTreeDocumentId(uri: Uri): String?
    fun getTreeUriName(uri: Uri): String?
    fun hasUriPermission(uri: Uri): Boolean
    fun getExternalStorageVolumeName(volumeId: String): String?
}
