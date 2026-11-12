package app.harvester.companion.settings.downloads

import android.content.ContentResolver
import android.net.Uri
import androidx.core.net.toUri
import java.io.File
import java.net.URLDecoder

private const val EXTERNAL_STORAGE_PROVIDER_AUTHORITY = "com.android.externalstorage.documents"
private const val PRIMARY_VOLUME_ID = "primary"
private const val DELIMITER = ":"

class DefaultDownloadLocationFormatter(private val fileUtils: AndroidFileUtils) : DownloadLocationFormatter {
    override fun getFriendlyPath(uriString: String): String {
        val uri = uriString.toUri()

        return when (uri.scheme) {
            null,
            ContentResolver.SCHEME_FILE -> formatFilePath(uri)
            ContentResolver.SCHEME_CONTENT -> formatContentUri(uri)
            else -> uriString
        }
    }

    private fun formatContentUri(uri: Uri): String {
        if (!fileUtils.hasUriPermission(uri)) {
            throw MissingUriPermission("Missing permissions for URI: $uri")
        }

        if (uri.authority == EXTERNAL_STORAGE_PROVIDER_AUTHORITY && fileUtils.isTreeUri(uri)) {
            return formatExternalStorageTreeUri(uri)
        }

        val displayName = fileUtils.getTreeUriName(uri)

        val descriptiveName = displayName?.takeIf {
            it.isNotBlank() && it != "/" && !it.all { char -> char.isDigit() }
        }

        val providerName = getProviderName(uri)

        val finalName =
            if (providerName.isNullOrBlank()) {
                descriptiveName ?: ""
            } else {
                descriptiveName?.let { "$providerName/$it" } ?: providerName
            }

        return if (finalName.isBlank()) "/" else "/$finalName"
    }

    private fun getProviderName(uri: Uri): String? {
        return when (uri.authority) {
            "org.nextcloud.documents" -> "Nextcloud"
            "com.google.android.apps.docs.storage" -> "Google Drive"
            "com.microsoft.skydrive.content.external" -> "OneDrive"
            "com.dropbox.android.dataprovider" -> "Dropbox"
            else -> uri.authority
        }
    }

    private fun formatFilePath(uri: Uri): String {
        val basePath = fileUtils.externalStorageDirectory.path
        val filePath = uri.path ?: return uri.toString()

        return if (filePath.startsWith(basePath)) {
            "~${filePath.substring(basePath.length)}"
        } else {
            "~/${File(filePath).name}"
        }
    }

    private fun formatExternalStorageTreeUri(uri: Uri): String {
        val documentId = fileUtils.getTreeDocumentId(uri) ?: return uri.toString()
        val decodedId = URLDecoder.decode(documentId, "UTF-8")
        val volumeId = decodedId.substringBefore(DELIMITER, "")
        val path = decodedId.substringAfter(DELIMITER, decodedId)

        val volumeName = fileUtils.getExternalStorageVolumeName(volumeId)
        return if (volumeName.isNullOrBlank() && path.isBlank()) {
            "~/"
        } else if (volumeName.isNullOrBlank() || volumeId.equals(PRIMARY_VOLUME_ID, ignoreCase = true)) {
            "~/$path"
        } else {
            "/$volumeName/$path"
        }
    }
}
