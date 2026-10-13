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

    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/FlummoxGamer/universal-harvester-companion/builds/version.json"

    fun checkAndPrompt(activity: Activity) {
        UHLog.section("UPDATE")
        UHLog.d("update", "check started")
        CoroutineScope(Dispatchers.IO).launch {
            val info = fetchManifest()
            UHLog.d("update", "manifest ${if (info == null) "unreachable" else "v${info.versionName} (${info.versionCode})"}")
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
                    .setMessage("New: v${info.versionName} (${info.versionCode})\nInstalled: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n\nDownload and install?")
                    .setPositiveButton("Install") { _, _ -> downloadAndInstall(activity, info) }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    private fun fetchManifest(): Manifest? {
        return try {
            val url = URL(MANIFEST_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.requestMethod = "GET"
            conn.connect()
            if (conn.responseCode !in 200..299) return null
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
        val progress = ProgressDialog(activity).apply {
            setTitle("Downloading update")
            setMessage("v${info.versionName}")
            isIndeterminate = false
            max = 100
            setCancelable(false)
            show()
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(info.apkUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 120000
                conn.requestMethod = "GET"
                conn.connect()
                if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                val out = File(dir, "update.apk")
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Long = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = ((read * 100) / total).toInt()
                                withContext(Dispatchers.Main) { progress.progress = pct }
                            }
                        }
                    }
                }
                conn.disconnect()
                withContext(Dispatchers.Main) { progress.dismiss() }
                UHLog.d("update", "downloaded ${out.length()}b")
                withContext(Dispatchers.Main) { installApk(activity, out) }
            } catch (e: Exception) {
                UHLog.throwable("update", "download failed", e)
                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    AlertDialog.Builder(activity)
                        .setTitle("Update failed")
                        .setMessage(e.message ?: "unknown error")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

private fun installApk(activity: Activity, apk: File) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("Permission needed")
                .setMessage("Allow this app to install unknown apps, then tap Install again.")
                .setPositiveButton("Open settings") { _, _ ->
                    val i = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                    activity.startActivity(i)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
    }

    val intent = Intent(activity, UpdateInstallActivity::class.java).apply {
        putExtra(UpdateInstallActivity.EXTRA_APK_PATH, apk.absolutePath)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    activity.startActivity(intent)
}

    private data class Manifest(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String
    )
                    }
