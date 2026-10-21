package app.harvester.companion

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private val MANIFEST_URL: String
    get() {
        val isDev = BuildConfig.VERSION_NAME.contains("-dev")
        val branch = if (isDev) "builds-dev" else "builds"
        val file = if (isDev) "version-dev.json" else "version.json"
        return "https://gh-proxy.com/https://raw.githubusercontent.com/FlummoxGamer/universal-harvester-companion/$branch/$file"
    }

    fun checkAndPrompt(activity: Activity) {
        UHLog.section("UPDATE")
        UHLog.d("update", "check started")
        CoroutineScope(Dispatchers.IO).launch {
            val info = fetchManifest()
            UHLog.d(
                "update",
                "manifest ${if (info == null) "unreachable" else "v${info.versionName} (${info.versionCode})"}"
            )
            if (info == null) {
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(activity)
                        .setTitle("Update check")
                        .setMessage("Could not reach the update server.")
                        .setPositiveButton("OK", null)
                        .show()
                }
                return@launch
            }
            if (info.versionCode <= BuildConfig.VERSION_CODE) {
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(activity)
                        .setTitle("Up to date")
                        .setMessage("You're on v${BuildConfig.VERSION_NAME}.")
                        .setPositiveButton("OK", null)
                        .show()
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(activity)
                    .setTitle("Update available")
                    .setMessage(
                        "New: v${info.versionName} (${info.versionCode})\n" +
                        "Installed: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n\n" +
                        "Download and install?"
                    )
                    .setPositiveButton("Install") { _, _ -> downloadAndInstall(activity, info) }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }


    fun resumePendingInstallIfPossible(activity: Activity) {
        val path = DebugPrefs.pendingApk(activity) ?: return
        val apk = File(path)
        if (!apk.exists() || apk.length() == 0L) {
            DebugPrefs.setPendingApk(activity, null)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {

            return
        }
        DebugPrefs.setPendingApk(activity, null)
        installApk(activity, apk)
    }

    private fun fetchManifest(): Manifest? {
        return try {
            val url = URL(MANIFEST_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Harvester-Updater/1.0 (Android)")
            conn.connect()
            if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val o = JSONObject(body)
            Manifest(
                versionCode = o.optInt("versionCode", 0),
                versionName = o.optString("versionName", ""),
                apkUrl = o.optString("apkUrl", ""),
                changelog = o.optString("changelog", "")
            ).takeIf { it.versionCode > 0 && it.apkUrl.isNotBlank() }
        } catch (e: Exception) {
            UHLog.throwable("update", "manifest fetch failed", e)
            null
        }
    }

private fun downloadAndInstall(activity: Activity, info: Manifest) {
    val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
    val out = File(dir, "uh_update.apk")
    val id = DownloadEngine.startDownload(activity, info.apkUrl, "uh_update.apk", "application/vnd.android.package-archive")
    
    android.widget.Toast.makeText(activity, "Downloading update...", android.widget.Toast.LENGTH_LONG).show()
}

    private fun installApk(activity: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {

            DebugPrefs.setPendingApk(activity, apk.absolutePath)

            AlertDialog.Builder(activity)
                .setTitle("Permission needed")
                .setMessage(
                    "Allow this app to install unknown apps. " +
                    "After enabling it in Settings, come back here — the " +
                    "install will continue automatically."
                )
                .setPositiveButton("Open settings") { _, _ ->
                    val i = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                    activity.startActivity(i)
                }
                .setNegativeButton("Cancel") { _, _ ->
                    DebugPrefs.setPendingApk(activity, null)
                }
                .show()
            return
        }

        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apk
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
            UHLog.d("update", "handed APK to installer")
        } catch (e: Exception) {
            UHLog.throwable("update", "installer launch failed", e)
            AlertDialog.Builder(activity)
                .setTitle("Install failed")
                .setMessage(e.message ?: "unknown error")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private data class Manifest(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String
    )
}
