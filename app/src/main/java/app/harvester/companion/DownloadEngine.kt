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
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

object DownloadEngine {
    private val jobs = mutableMapOf<String, Job>()
    private val calls = mutableMapOf<String, Call>()
    private val streams = mutableMapOf<String, InputStream>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun startDownload(ctx: Context, url: String, filename: String, mime: String, base64Data: String? = null, userAgent: String? = null, referer: String? = null): String {
        val id = UUID.randomUUID().toString()
        val task = DownloadStore.Task(id, url, filename, mime, base64Data = base64Data, userAgent = userAgent, referer = referer)
        DownloadStore.addTask(task)
        DownloadService.start(ctx)
        resumeDownload(ctx, id)
        return id
    }

    fun startStreamDownload(ctx: Context, filename: String, mime: String, stream: InputStream, contentLength: Long, userAgent: String?, referer: String?): String {
        val id = UUID.randomUUID().toString()
        val task = DownloadStore.Task(id, "stream", filename, mime, userAgent = userAgent, referer = referer)
        task.totalBytes = contentLength
        DownloadStore.addTask(task)
        streams[id] = stream
        DownloadService.start(ctx)
        
        jobs[id] = CoroutineScope(Dispatchers.IO).launch {
            var targetUri: Uri? = null
            try {
                targetUri = saveToMediaStore(ctx, task.filename, task.mime)
                val outputStream = ctx.contentResolver.openOutputStream(targetUri) ?: throw Exception("Cannot open file")
                
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var lastUpdate = System.currentTimeMillis()
                var bytesSinceLastUpdate = 0L

                outputStream.use { out ->
                    stream.use { input ->
                        while (currentCoroutineContext().isActive) {
                            bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            out.write(buffer, 0, bytesRead)
                            task.bytesDownloaded += bytesRead
                            bytesSinceLastUpdate += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 500) {
                                val speed = (bytesSinceLastUpdate * 1000) / (now - lastUpdate)
                                val eta = if (speed > 0 && task.totalBytes > 0) (task.totalBytes - task.bytesDownloaded) / speed else -1L
                                
                                DownloadStore.updateTask(task.id) {
                                    it.speed = speed
                                    it.etaSeconds = eta
                                }
                                DownloadService.updateNotification(ctx, task.id)
                                
                                lastUpdate = now
                                bytesSinceLastUpdate = 0L
                            }
                        }
                    }
                }

                targetUri?.let { finishMediaStore(ctx, it) }
                DownloadStore.updateTask(task.id) { it.status = DownloadStore.Status.SUCCESS }
                DownloadService.notifyComplete(ctx, task.id, task.filename)
                DownloadStore.removeTask(task.id)
            } catch (e: Exception) {
                targetUri?.let { deletePartialFile(ctx, it) }
                DownloadStore.updateTask(task.id) { it.status = DownloadStore.Status.FAILED; it.error = e.message }
                DownloadService.notifyFailed(ctx, task.id, task.filename, e.message ?: "Unknown error")
            } finally {
                streams.remove(id)
            }
        }
        return id
    }

    fun pauseDownload(ctx: Context, id: String) {
        calls[id]?.cancel()
        calls.remove(id)
        jobs[id]?.cancel()
        jobs.remove(id)
        streams[id]?.close()
        streams.remove(id)
        DownloadStore.updateTask(id) { it.status = DownloadStore.Status.PAUSED }
        DownloadService.updateNotification(ctx, id)
    }

    fun cancelDownload(ctx: Context, id: String) {
        calls[id]?.cancel()
        calls.remove(id)
        jobs[id]?.cancel()
        jobs.remove(id)
        streams[id]?.close()
        streams.remove(id)
        
        DownloadStore.updateTask(id) { it.status = DownloadStore.Status.CANCELLED }
        val task = DownloadStore.getTask(id)
        if (task != null) {
            DownloadStore.removeTask(id)
        }
        DownloadService.cancelNotification(ctx, id)
    }

    fun resumeDownload(ctx: Context, id: String) {
        val task = DownloadStore.getTask(id) ?: return
        if (jobs[id]?.isActive == true) return

        DownloadStore.updateTask(id) { it.status = DownloadStore.Status.RUNNING }
        DownloadService.updateNotification(ctx, id)

        jobs[id] = CoroutineScope(Dispatchers.IO).launch {
            var targetUri: Uri? = null
            try {
                if (task.base64Data != null) {
                    val bytes = android.util.Base64.decode(task.base64Data, android.util.Base64.DEFAULT)
                    targetUri = saveToMediaStore(ctx, task.filename, task.mime)
                    ctx.contentResolver.openOutputStream(targetUri)?.use { it.write(bytes) }
                    if (targetUri != null) finishMediaStore(ctx, targetUri)
                    DownloadStore.updateTask(task.id) {
                        it.bytesDownloaded = bytes.size.toLong()
                        it.totalBytes = bytes.size.toLong()
                        it.status = DownloadStore.Status.SUCCESS
                    }
                    DownloadService.notifyComplete(ctx, task.id, task.filename)
                    DownloadStore.removeTask(task.id)
                } else {
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

                        targetUri = saveToMediaStore(ctx, task.filename, task.mime)
                        val outputStream = ctx.contentResolver.openOutputStream(targetUri, if (task.bytesDownloaded > 0) "wa" else "w")
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
                                        DownloadService.updateNotification(ctx, task.id)
                                        
                                        lastUpdate = now
                                        bytesSinceLastUpdate = 0L
                                    }
                                }
                            }
                        }

                        targetUri?.let { finishMediaStore(ctx, it) }
                        DownloadStore.updateTask(task.id) { it.status = DownloadStore.Status.SUCCESS }
                        DownloadService.notifyComplete(ctx, task.id, task.filename)
                        DownloadStore.removeTask(task.id)
                        calls.remove(task.id)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                targetUri?.let { deletePartialFile(ctx, it) } 
                DownloadStore.updateTask(id) { 
                    it.status = DownloadStore.Status.FAILED
                    it.error = e.message 
                }
                DownloadService.notifyFailed(ctx, id, task.filename, e.message ?: "Unknown error")
            }
        }
    }

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
        return ctx.contentResolver.insert(collection, values) ?: throw Exception("MediaStore insert failed")
    }

    private fun finishMediaStore(ctx: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            ctx.contentResolver.update(uri, done, null, null)
        }
    }

    private fun deletePartialFile(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {}
    }
}
