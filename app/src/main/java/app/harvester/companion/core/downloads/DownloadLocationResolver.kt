package app.harvester.companion.core.downloads

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.UHLog
import app.harvester.companion.settings.downloads.DefaultAndroidFileUtils
import java.io.File

object DownloadLocationResolver {

    fun defaultPublicDownloadsPath(): String =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path

    fun resolveDefaultConfiguredDirectory(context: Context): String {
        val configuredDefault = SettingsPrefs.getString(context, SettingsPrefs.K_DL_LOCATION, "").trim()
        if (configuredDefault.isNotBlank()) {
            if (configuredDefault.startsWith("content://")) {
                if (isValidSafTree(context, configuredDefault)) {
                    return configuredDefault
                }
                UHLog.w("download_warn", "Configured default SAF download URI is unavailable or unwritable: $configuredDefault")
            } else {
                val dir = File(configuredDefault)
                if ((dir.exists() || dir.mkdirs()) && dir.canWrite()) {
                    return configuredDefault
                }
                UHLog.w("download_warn", "Configured default download directory is not writable: $configuredDefault")
            }
        }
        return defaultPublicDownloadsPath()
    }

    fun resolveTargetDirectory(context: Context): String {
        val customUriStr = SettingsPrefs.getString(context, "custom_download_uri", "").trim()
        if (customUriStr.isNotBlank()) {
            if (customUriStr.startsWith("content://")) {
                if (isValidSafTree(context, customUriStr)) {
                    return customUriStr
                }
                UHLog.w("download_warn", "Custom download folder permission missing or unwritable ($customUriStr); using default download location.")
            } else {
                val dir = File(customUriStr)
                if ((dir.exists() || dir.mkdirs()) && dir.canWrite()) {
                    return customUriStr
                }
                UHLog.w("download_warn", "Custom download folder is not writable ($customUriStr); using default download location.")
            }
        }
        return resolveDefaultConfiguredDirectory(context)
    }

    fun resolveDirectoryOrDefault(context: Context, explicitPath: String?): String {
        val candidate = explicitPath?.trim().orEmpty()
        if (candidate.isNotBlank()) {
            if (candidate.startsWith("content://")) {
                if (isValidSafTree(context, candidate)) return candidate
            } else {
                val dir = File(candidate)
                if ((dir.exists() || dir.mkdirs()) && dir.canWrite()) return candidate
            }
        }
        return resolveTargetDirectory(context)
    }

    fun isValidSafTree(context: Context, uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            val fileUtils = DefaultAndroidFileUtils(context)
            if (!fileUtils.hasUriPermission(uri)) return false
            val tree = DocumentFile.fromTreeUri(context, uri)
            tree != null && tree.exists() && tree.canWrite()
        } catch (e: Exception) {
            UHLog.throwable("download_warn", "Failed validating SAF download URI: $uriString", e)
            false
        }
    }

    fun missingPermissionsForDownload(context: Context): Array<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return emptyArray()
        }
        val customUriStr = SettingsPrefs.getString(context, "custom_download_uri", "").trim()
        if (customUriStr.startsWith("content://") && isValidSafTree(context, customUriStr)) {
            return emptyArray()
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) emptyArray() else arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    fun safFileExists(context: Context, treeUriString: String, fileName: String?): Boolean {
        if (fileName.isNullOrBlank() || !treeUriString.startsWith("content://")) return false
        return try {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriString)) ?: return false
            tree.findFile(fileName)?.exists() == true
        } catch (e: Exception) {
            UHLog.w("download_warn", "Failed checking SAF file existence: ${e.message}")
            false
        }
    }

    fun findSafFileUri(context: Context, treeUriString: String, fileName: String?): Uri? {
        if (fileName.isNullOrBlank() || !treeUriString.startsWith("content://")) return null
        return try {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriString)) ?: return null
            tree.findFile(fileName)?.uri
        } catch (e: Exception) {
            UHLog.w("download_warn", "Failed resolving SAF file URI: ${e.message}")
            null
        }
    }

    fun deleteSafFile(context: Context, treeUriString: String, fileName: String?): Boolean {
        if (fileName.isNullOrBlank() || !treeUriString.startsWith("content://")) return false
        return try {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriString)) ?: return false
            val target = tree.findFile(fileName) ?: return false
            target.delete()
        } catch (e: Exception) {
            UHLog.w("download_warn", "Failed deleting SAF file: ${e.message}")
            false
        }
    }

    fun renameSafFile(context: Context, treeUriString: String, oldName: String?, newName: String): Boolean {
        if (oldName.isNullOrBlank() || !treeUriString.startsWith("content://")) return false
        return try {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriString)) ?: return false
            val target = tree.findFile(oldName) ?: return false
            target.renameTo(newName)
        } catch (e: Exception) {
            UHLog.w("download_warn", "Failed renaming SAF file: ${e.message}")
            false
        }
    }
}
