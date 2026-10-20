package app.harvester.companion

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.net.URLDecoder

object DownloadEngine {
    private val jobs = mutableMapOf<String, Job>()
    private val calls = mutableMapOf<String, Call>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun startDownload(ctx: Context, url: String, filename: String, mime: String, base64Data: String? = null, userAgent: String? = null, referer: String? = null): String {
        val id = UUID.randomUUID().toString()
        val task = DownloadStore.Task(id, url, filename, mime, base64Data = base64Data, userAgent = userAgent)
        DownloadStore.addTask(task)
        DownloadService.start(ctx)
        resumeDownload(ctx, id, referer)
        return id
    }

    fun pauseDownload(id: String) {
        calls[id]?.cancel()
        calls.remove(id)
        jobs[id]?.cancel()
        jobs.remove(id)
        DownloadStore.updateTask(id) { it.status = DownloadStore.Status.PAUSED }
        DownloadService.updateNotification()
    }

    fun cancelDownload(ctx: Context, id: String) {
        calls[id]?.cancel()
        calls.remove(id)
        jobs[id]?.cancel()
        jobs.remove(id)
        DownloadStore.updateTask(id) { it.status = DownloadStore.Status.CANCELLED }
        
        val task = DownloadStore.getTask(id)
        if (task != null) {
            deletePartialFile(ctx, task.filename)
            DownloadStore.removeTask(id)
        }
        DownloadService.updateNotification()
    }

    fun resumeDownload(ctx: Context, id: String, referer: String? = null) {
        val task = DownloadStore.getTask(id) ?: return
        if (jobs[id]?.isActive == true) return

        DownloadStore.updateTask(id) { it.status = DownloadStore.Status.RUNNING }
        DownloadService.updateNotification()

        jobs[id] = CoroutineScope(Dispatchers.IO).launch {
            try {
                if (task.base64Data != null) {
                    handleBlobDownload(ctx, task)
                } else {
                    handleNetworkDownload(ctx, task, referer)
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
        try {
            val bytes = android.util.Base64.decode(task.base64Data, android.util.Base64.DEFAULT)
            val os = getOutputStream(ctx, task.filename)
            os?.use { it.write(bytes) }
            DownloadStore.updateTask(task.id) {
                it.bytesDownloaded = bytes.size.toLong()
                it.totalBytes = bytes.size.toLong()
                it.status = DownloadStore.Status.SUCCESS
            }
        } catch (e: Exception) {
            DownloadStore.updateTask(task.id) { it.status = DownloadStore.Status.FAILED; it.error = e.message }
        } finally {
            DownloadService.updateNotification()
            DownloadStore.removeTask(task.id)
        }
    }

    private suspend fun handleNetworkDownload(ctx: Context, task: DownloadStore.Task, referer: String?) {
        val requestBuilder = Request.Builder().url(task.url)
        
        val cookie = android.webkit.CookieManager.getInstance().getCookie(task.url)
        if (!cookie.isNullOrBlank()) requestBuilder.addHeader("Cookie", cookie)
        if (!task.userAgent.isNullOrBlank()) requestBuilder.addHeader("User-Agent", task.userAgent)
        else requestBuilder.addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
        if (!referer.isNullOrBlank()) requestBuilder.addHeader("Referer", referer)

        if (task.bytesDownloaded > 0) {
            requestBuilder.addHeader("Range", "bytes=${task.bytesDownloaded}-")
        }

        val call = client.newCall(requestBuilder.build())
        calls[task.id] = call

        call.execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            
            val body = response.body ?: throw Exception("Empty body")
            
            // If server ignores Range and sends 200, we must restart from 0
            if (response.code == 200 && task.bytesDownloaded > 0) {
                task.bytesDownloaded = 0
            }

            val total = if (response.code == 206) task.bytesDownloaded + body.contentLength() else body.contentLength()
            DownloadStore.updateTask(task.id) { it.totalBytes = total }

            val inputStream = body.byteStream()
            val outputStream = getOutputStream(ctx, task.filename, append = task.bytesDownloaded > 0) 
                ?: throw Exception("Cannot open file")

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var lastUpdate = System.currentTimeMillis()
            var bytesSinceLastUpdate = 0L

            outputStream.use { out ->
                inputStream.use { input ->
                    while (currentCoroutineContext().isActive) {
                        bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        out.write(buffer, 0, bytesRead)
                        task.bytesDownloaded += bytesRead
                        bytesSinceLastUpdate += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 500) {
                            val speed = (bytesSinceLastUpdate * 1000) / (now - lastUpdate)
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
        }
        calls.remove(task.id)
    }

    private fun getOutputStream(ctx: Context, filename: String, append: Boolean = false): OutputStream? {
        val customUriStr = SettingsPrefs.getString(ctx, "custom_download_uri", "")
        return if (customUriStr.isNotBlank()) {
            val treeUri = Uri.parse(customUriStr)
            val doc = DocumentFile.fromTreeUri(ctx, treeUri)?.createFile("application/octet-stream", filename)
            if (doc != null) ctx.contentResolver.openOutputStream(doc.uri, if (append) "wa" else "w") else null
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            FileOutputStream(File(dir, filename), append)
        }
    }
    
    private fun deletePartialFile(ctx: Context, filename: String) {
        try {
            val customUriStr = SettingsPrefs.getString(ctx, "custom_download_uri", "")
            if (customUriStr.isNotBlank()) {
                val treeUri = Uri.parse(customUriStr)
                DocumentFile.fromTreeUri(ctx, treeUri)?.findFile(filename)?.delete()
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                File(dir, filename).delete()
            }
        } catch (_: Exception) {}
    }
}
