package app.harvester.companion

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

object DownloadEngine {
    private val jobs = mutableMapOf<String, Job>()
    private val calls = mutableMapOf<String, Call>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // startDownload
    fun startDownload(ctx: Context, url: String, filename: String, mime: String, base64Data: String? = null, userAgent: String? = null, referer: String? = null): String {
        val id = UUID.randomUUID().toString()
        val task = DownloadStore.Task(id, url, filename, mime, base64Data = base64Data, userAgent = userAgent, referer = referer)
        DownloadStore.addTask(task)
        DownloadService.start(ctx)
        resumeDownload(ctx, id)
        return id
    }

    // pauseDownload
    fun pauseDownload(id: String) {
        calls[id]?.cancel()
        calls.remove(id)
        jobs[id]?.cancel()
        jobs.remove(id)
        DownloadStore.updateTask(id) { it.status = DownloadStore.Status.PAUSED }
        DownloadService.updateNotification(ctx = UHApp().applicationContext)
    }

    // cancelDownload
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
        DownloadService.updateNotification(ctx)
    }

    // resumeDownload
    fun resumeDownload(ctx: Context, id: String) {
        val task = DownloadStore.getTask(id) ?: return
        if (jobs[id]?.isActive == true) return

        DownloadStore.updateTask(id) { it.status = DownloadStore.Status.RUNNING }
        DownloadService.updateNotification(ctx)

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
                DownloadService.updateNotification(ctx)
            }
        }
    }

    // handleBlobDownload
    private fun handleBlobDownload(ctx: Context, task: DownloadStore.Task) {
        try {
            val bytes = android.util.Base64.decode(task.base64Data, android.util.Base64.DEFAULT)
            val uri = saveToMediaStore(ctx, task.filename, task.mime)
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            
            DownloadStore.updateTask(task.id) {
                it.bytesDownloaded = bytes.size.toLong()
                it.totalBytes = bytes.size.toLong()
                it.status = DownloadStore.Status.SUCCESS
            }
        } catch (e: Exception) {
            DownloadStore.updateTask(task.id) { it.status = DownloadStore.Status.FAILED; it.error = e.message }
        } finally {
            DownloadService.updateNotification(ctx)
            DownloadStore.removeTask(task.id)
        }
    }

    // handleNetworkDownload
    private suspend fun handleNetworkDownload(ctx: Context, task: DownloadStore.Task) {
        val requestBuilder = Request.Builder().url(task.url)
        
        val cookie = CookieManager.getInstance().getCookie(task.url)
        if (!cookie.isNullOrBlank()) requestBuilder.addHeader("Cookie", cookie)
        if (!task.userAgent.isNullOrBlank()) requestBuilder.addHeader("User-Agent", task.userAgent)
        else requestBuilder.addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
        if (!task.referer.isNullOrBlank()) requestBuilder.addHeader("Referer", task.referer)

        if (task.bytesDownloaded > 0) {
            requestBuilder.addHeader("Range", "bytes=${task.bytesDownloaded}-")
        }

        val call = client.newCall(requestBuilder.build())
        calls[task.id] = call

        call.execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            
            val body = response.body ?: throw Exception("Empty body")
            
            if (response.code == 200 && task.bytesDownloaded > 0) {
                task.bytesDownloaded = 0
            }

            val total = if (response.code == 206) task.bytesDownloaded + body.contentLength() else body.contentLength()
            DownloadStore.updateTask(task.id) { it.totalBytes = total }

            val uri = saveToMediaStore(ctx, task.filename, task.mime)
            val outputStream = ctx.contentResolver.openOutputStream(uri, if (task.bytesDownloaded > 0) "wa" else "w")
                ?: throw Exception("Cannot open file")

            val inputStream = body.byteStream()
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
                            DownloadService.updateNotification(ctx)
                            
                            lastUpdate = now
                            bytesSinceLastUpdate = 0L
                        }
                    }
                }
            }

            DownloadStore.updateTask(task.id) { it.status = DownloadStore.Status.SUCCESS }
            DownloadService.updateNotification(ctx)
            DownloadStore.removeTask(task.id)
            DownloadService.notifyComplete(ctx, task.filename)
            calls.remove(task.id)
        }
    }

    // saveToMediaStore
    private fun saveToMediaStore(ctx: Context, filename: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Harvester")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = ctx.contentResolver.insert(collection, values) ?: throw Exception("MediaStore insert failed")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            ctx.contentResolver.update(uri, done, null, null)
        }
        return uri
    }

    // deletePartialFile
    private fun deletePartialFile(ctx: Context, filename: String) {
        try {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            val args = arrayOf(filename, Environment.DIRECTORY_DOWNLOADS + "/Harvester/")
            ctx.contentResolver.delete(collection, selection, args)
        } catch (_: Exception) {}
    }
}
