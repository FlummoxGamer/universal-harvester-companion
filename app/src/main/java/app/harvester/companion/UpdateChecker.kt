package app.harvester.companion

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object UpdateChecker {

    // Point this at your public builds repo.
    // Format: https://raw.githubusercontent.com/<user>/<repo>/<branch>/version.json
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/CHANGE_ME/universal-harvester-companion-builds/main/version.json"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun checkAndPrompt(activity: Activity) {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            val info = fetchManifest()
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
                    .setMessage("New version: v${info.versionName} (code ${info.versionCode}).\nInstalled: v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}).\n\nDownload and install?")
                    .setPositiveButton("Install") { _, _ -> downloadAndInstall(activity, info) }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    private fun fetchManifest(): Manifest? {
        return try {
            val req = Request.Builder().url(MANIFEST_URL).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val o = JSONObject(body)
                Manifest(
                    versionCode = o.optInt("versionCode", 0),
                    versionName = o.optString("versionName", ""),
                    apkUrl = o.optString("apkUrl", ""),
                    changelog = o.optString("changelog", "")
                ).takeIf { it.versionCode > 0 && it.apkUrl.isNotBlank() }
            }
        } catch (_: Exception) { null }
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
                val req = Request.Builder().url(info.apkUrl).get().build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                    val body = resp.body ?: throw Exception("empty body")
                    val total = body.contentLength()
                    val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                    val out = File(dir, "update.apk")
                    body.byteStream().use { input ->
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
                    withContext(Dispatchers.Main) { progress.dismiss() }
                    installApk(activity, out)
                }
            } catch (e: Exception) {
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
                        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}"))
                        activity.startActivity(i)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
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
        activity.startActivity(intent)
    }

    private data class Manifest(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String
    )
}
