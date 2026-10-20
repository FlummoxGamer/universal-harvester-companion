package app.harvester.companion

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object DownloadEngine {
    private val jobs = mutableMapOf<String, Job>()

    fun startDownload(ctx: Context, url: String, filename: String, mime: String, base64Data: String? = null, userAgent: String? = null): String {
        val id = UUID.randomUUID().toString()
        val task = DownloadStore.Task(id, url, filename, mime, base64Data = base64Data, userAgent = userAgent)
        DownloadStore.addTask(task)
        resumeDownload(ctx, id)
        return id
    }

    fun pauseDownload(id: String) {
        jobs[id]?.cancel()
        jobs.remove(id)
        DownloadStore.updateTask(id) { it.status = DownloadStore.Status.PAUSED }
        DownloadService.updateNotification()
    }

    fun cancelDownload(ctx: Context, id: String) {
        jobs[id]?.cancel()
        jobs.remove(id)
        DownloadStore.updateTask(id) { it.status = DownloadStore.Status.CANCELLED }
        
        val task = DownloadStore.getTask(id)
        if (task != null) {
            deletePartialFile(ctx, task.filename)
            DownloadStore.removeTask(id)
        }
        DownloadService.cancelNotification(id)
    }

    fun resumeDownload(ctx: Context, id: String) {
        val task = DownloadStore.getTask(id) ?: return
        if (jobs[id]?.isActive == true) return

        DownloadStore.updateTask(id) { it.status = DownloadStore.Status.RUNNING }
        DownloadService.updateNotification()

        jobs[id] = CoroutineScope(Dispatchers.IO).launch {
            try {
                if (task.base64Data != null) {
                    handleBlobDownload(ctx, task)
                } else {
                    handleNetworkDownload(ctx, task)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DownloadStore.updateTask(id) { 
                    it.status = DownloadStore.Status.FAILED
                    it.error = e.message 
                }
                DownloadService.updateNotification()
            }
        }
    }

    private fun handleBlobDownload(ctx: Context, task: DownloadStore.Task) {
        val bytes = android.util.Base64.decode(task.base64Data, android.util.Base64.DEFAULT)
        val os = getOutputStream(ctx, task.filename)
        os?.use { it.write(bytes) }
        DownloadStore.updateTask(task.id) {
            it.bytesDownloaded = bytes.size.toLong()
            it.totalBytes = bytes.size.toLong()
            it.status = DownloadStore.Status.SUCCESS
        }
        DownloadService.updateNotification()
        DownloadStore.removeTask(task.id)
    }

    private fun handleNetworkDownload(ctx: Context, task: DownloadStore.Task) {
        val conn = URL(task.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        
        try {
            val cookie = android.webkit.CookieManager.getInstance().getCookie(task.url)
            if (!cookie.isNullOrBlank()) conn.setRequestProperty("Cookie", cookie)
            if (!task.userAgent.isNullOrBlank()) conn.setRequestProperty("User-Agent", task.userAgent)
            else conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)")

            if (task.bytesDownloaded > 0) {
                conn.setRequestProperty("Range", "bytes=${task.bytesDownloaded}-")
            }

            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) throw Exception("HTTP $code")

            val total = if (code == 206) {
                task.bytesDownloaded + conn.contentLengthLong
            } else {
                conn.contentLengthLong
            }
            
            DownloadStore.updateTask(task.id) { it.totalBytes = total }

            val inputStream = conn.inputStream
            val outputStream = getOutputStream(ctx, task.filename, append = task.bytesDownloaded > 0) ?: throw Exception("Cannot open file")

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var lastUpdate = System.currentTimeMillis()
            var bytesSinceLastUpdate = 0L

            outputStream.use { out ->
                inputStream.use { input ->
                    while (isActive) {
                        bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        out.write(buffer, 0, bytesRead)
                        task.bytesDownloaded += bytesRead
                        bytesSinceLastUpdate += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 500) {
                            val timeDiff = now - lastUpdate
                            val speed = (bytesSinceLastUpdate * 1000) / timeDiff
                            val eta = if (speed > 0 && total > 0) (total - task.bytesDownloaded) / speed else -1L
                            
                            DownloadStore.updateTask(task.id) {
                                it.speed = speed
                                it.etaSeconds = eta
                            }
                            DownloadService.updateNotification()
                            
                            lastUpdate = now
                            bytesSinceLastUpdate = 0L
                        }
                    }
                }
            }

            DownloadStore.updateTask(task.id) { it.status = DownloadStore.Status.SUCCESS }
            DownloadService.updateNotification()
            DownloadStore.removeTask(task.id)

        } finally {
            conn.disconnect()
        }
    }

    private fun getOutputStream(ctx: Context, filename: String, append: Boolean = false): OutputStream? {
        val customUriStr = SettingsPrefs.getString(ctx, "custom_download_uri", "")
        return if (customUriStr.isNotBlank()) {
            val treeUri = Uri.parse(customUriStr)
            val doc = DocumentFile.fromTreeUri(ctx, treeUri)?.createFile("application/octet-stream", filename)
            if (doc != null) {
                ctx.contentResolver.openOutputStream(doc.uri, if (append) "wa" else "w")
            } else null
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(dir, filename)
            FileOutputStream(file, append)
        }
    }
    
    private fun deletePartialFile(ctx: Context, filename: String) {
        try {
            val customUriStr = SettingsPrefs.getString(ctx, "custom_download_uri", "")
            if (customUriStr.isNotBlank()) {
                val treeUri = Uri.parse(customUriStr)
                val doc = DocumentFile.fromTreeUri(ctx, treeUri)?.findFile(filename)
                doc?.delete()
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(dir, filename)
                if (file.exists()) file.delete()
            }
        } catch (_: Exception) {}
    }
}
