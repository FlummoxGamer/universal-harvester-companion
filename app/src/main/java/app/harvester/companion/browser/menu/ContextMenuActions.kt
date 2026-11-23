package app.harvester.companion.browser.menu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import app.harvester.companion.ImagePreviewActivity
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.core.downloads.DownloadLocationResolver
import app.harvester.companion.core.network.UhHttpClient
import app.harvester.companion.privacy.UhShieldEngine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.action.DownloadAction
import mozilla.components.browser.state.state.content.DownloadState
import okhttp3.Request
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Modular action helpers powering the redesigned UH Long-Press Context Menu and
 * Fullscreen Image Previewer:
 * - Real bitmap clipboard copy via FileProvider URI
 * - Real image file sharing via FileProvider URI
 * - Smart downloads routed through UH DownloadUseCases + DownloadLocationResolver
 * - Real ML Kit OCR over OkHttp-fetched Bitmaps & data: URIs
 * - Google Lens reverse image search inside a UH tab
 */
object ContextMenuActions {

    private const val SHARED_MEDIA_DIR = "shared_media"
    private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024 // 20 MB safety bound

    data class FetchedMedia(
        val bytes: ByteArray,
        val mimeType: String,
        val extension: String,
    )

    fun copyText(context: Context, label: String, text: String, toastMessage: String = "Copied to clipboard") {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            UHLog.w("context", "copyText failed: ${e.message}")
        }
    }

    fun shareText(context: Context, text: String, title: String = "Share link") {
        try {
            val clean = UhShieldEngine.cleanTrackingUrl(text, stripTrackers = true, deAmp = true)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, clean)
            }
            context.startActivity(Intent.createChooser(intent, title))
        } catch (e: Exception) {
            UHLog.w("context", "shareText failed: ${e.message}")
        }
    }

    fun openFullscreenPreview(context: Context, imageUrl: String, referer: String?, title: String?) {
        try {
            val intent = Intent(context, ImagePreviewActivity::class.java).apply {
                putExtra("url", imageUrl)
                if (!referer.isNullOrBlank()) putExtra("referer", referer)
                if (!title.isNullOrBlank()) putExtra("title", title)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            UHLog.w("context", "openFullscreenPreview failed: ${e.message}")
        }
    }

    fun buildGoogleLensUrl(imageUrl: String): String {
        val encoded = try {
            URLEncoder.encode(imageUrl, "UTF-8")
        } catch (_: Exception) {
            imageUrl
        }
        return "https://lens.google.com/uploadbyurl?url=$encoded"
    }

    fun resolveDisplayFilename(url: String, title: String?, fallbackPrefix: String, defaultExt: String): String {
        val cleanedTitle = title?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("http") }
        if (cleanedTitle != null && cleanedTitle.length <= 48) {
            return cleanedTitle
        }
        if (url.startsWith("data:") || url.startsWith("blob:")) {
            val ext = guessExtensionFromUrlOrMime(url, null, defaultExt)
            return "${fallbackPrefix}_${System.currentTimeMillis()}.$ext"
        }
        val decodedSegment = try {
            Uri.decode(url.substringBefore('?').substringBefore('#').substringAfterLast('/'))
        } catch (_: Exception) {
            ""
        }.trim()

        if (decodedSegment.isNotBlank() && decodedSegment.length in 3..64 && decodedSegment.contains('.')) {
            return sanitizeFilename(decodedSegment)
        }
        val ext = guessExtensionFromUrlOrMime(url, null, defaultExt)
        return "${fallbackPrefix}_${System.currentTimeMillis()}.$ext"
    }

    fun extractHostLabel(url: String): String {
        if (url.startsWith("data:")) return "Embedded image"
        if (url.startsWith("blob:")) return "Page media"
        return try {
            URI(url).host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url.take(40)
        } catch (_: Exception) {
            url.take(40)
        }
    }

    fun enqueueSmartDownload(
        context: Context,
        url: String,
        title: String? = null,
        fallbackPrefix: String = "download",
        defaultMime: String = "application/octet-stream",
        defaultExt: String = "bin",
        isPrivate: Boolean = false,
    ) {
        try {
            val appCtx = context.applicationContext
            val components = (appCtx as UHApp).components
            val ext = guessExtensionFromUrlOrMime(url, defaultMime, defaultExt)
            val mime = guessMimeType(ext, defaultMime)
            var fileName = resolveDisplayFilename(url, title, fallbackPrefix, ext)
            if (!fileName.contains('.')) {
                fileName = "$fileName.$ext"
            }
            val targetDir = DownloadLocationResolver.resolveTargetDirectory(appCtx)
            val downloadState = DownloadState(
                url = url,
                fileName = sanitizeFilename(fileName),
                contentType = mime,
                directoryPath = targetDir,
                private = isPrivate,
            )
            components.store.dispatch(DownloadAction.AddDownloadAction(downloadState))
            Toast.makeText(context, "Downloading $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            UHLog.e("context", "enqueueSmartDownload failed: ${e.message}")
            Toast.makeText(context, "Failed to start download", Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun copyImageBitmapToClipboard(context: Context, imageUrl: String, referer: String?): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val file = fetchImageToCacheFile(context, imageUrl, referer, prefix = "clip") ?: return@withContext false
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                withContext(Dispatchers.Main) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newUri(context.contentResolver, "Image", uri)
                    cm.setPrimaryClip(clip)
                    Toast.makeText(context, "Image copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                true
            } catch (e: Exception) {
                UHLog.e("context", "copyImageBitmapToClipboard failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Could not copy image", Toast.LENGTH_SHORT).show()
                }
                false
            }
        }
    }

    suspend fun shareImageFile(context: Context, imageUrl: String, referer: String?): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val file = fetchImageToCacheFile(context, imageUrl, referer, prefix = "share") ?: return@withContext false
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                val mime = guessMimeType(file.extension, "image/*")
                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share image"))
                }
                true
            } catch (e: Exception) {
                UHLog.e("context", "shareImageFile failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Could not share image", Toast.LENGTH_SHORT).show()
                }
                false
            }
        }
    }

    /**
     * Downloads an image via OkHttp (or decodes a data: URI) and runs ML Kit Text Recognition
     * directly on the decoded Bitmap.
     */
    suspend fun extractTextFromImage(imageUrl: String, referer: String?): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val fetched = fetchMediaBytes(imageUrl, referer)
                    ?: return@withContext Result.failure(IllegalStateException("Unable to fetch image"))
                val bitmap = decodeScaledBitmap(fetched.bytes, maxDim = 2048)
                    ?: return@withContext Result.failure(IllegalStateException("Unsupported image format"))
                try {
                    val text = runMlKitOcr(bitmap)
                    Result.success(text.trim())
                } finally {
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                UHLog.e("context", "extractTextFromImage failed: ${e.message}")
                Result.failure(e)
            }
        }
    }

    private suspend fun runMlKitOcr(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val input = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(input)
            .addOnSuccessListener { visionText ->
                try { recognizer.close() } catch (_: Exception) {}
                if (cont.isActive) cont.resume(visionText.text.orEmpty())
            }
            .addOnFailureListener { err ->
                try { recognizer.close() } catch (_: Exception) {}
                if (cont.isActive) cont.resume("")
                UHLog.w("context", "ML Kit OCR failed: ${err.message}")
            }
    }

    private fun fetchImageToCacheFile(
        context: Context,
        imageUrl: String,
        referer: String?,
        prefix: String,
    ): File? {
        val fetched = fetchMediaBytes(imageUrl, referer) ?: return null
        val dir = File(context.applicationContext.cacheDir, SHARED_MEDIA_DIR)
        if (!dir.exists()) dir.mkdirs()
        pruneOldSharedFiles(dir)

        val ext = fetched.extension.ifBlank { "png" }
        val file = File(dir, "${prefix}_${System.currentTimeMillis()}.$ext")
        // For webp/avif/unknown formats when copying to clipboard, re-encode to PNG for universal paste compatibility
        if (prefix == "clip" && ext !in setOf("png", "jpg", "jpeg", "gif")) {
            val bmp = BitmapFactory.decodeByteArray(fetched.bytes, 0, fetched.bytes.size)
            if (bmp != null) {
                val pngFile = File(dir, "${prefix}_${System.currentTimeMillis()}.png")
                try {
                    pngFile.outputStream().use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    return pngFile
                } finally {
                    bmp.recycle()
                }
            }
        }
        file.writeBytes(fetched.bytes)
        return file
    }

    private fun pruneOldSharedFiles(dir: File) {
        try {
            val cutoff = System.currentTimeMillis() - 15 * 60 * 1000L
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) f.delete()
            }
        } catch (_: Exception) {}
    }

    private fun fetchMediaBytes(url: String, referer: String?): FetchedMedia? {
        if (url.startsWith("data:", ignoreCase = true)) {
            val comma = url.indexOf(',')
            if (comma <= 0) return null
            val meta = url.substring(5, comma).lowercase(Locale.ROOT)
            val mime = meta.substringBefore(';').ifBlank { "image/png" }
            val payload = url.substring(comma + 1)
            val bytes = if (meta.contains(";base64")) {
                Base64.decode(payload, Base64.DEFAULT)
            } else {
                Uri.decode(payload).toByteArray(Charsets.UTF_8)
            }
            val ext = guessExtensionFromUrlOrMime(url, mime, "png")
            return FetchedMedia(bytes, mime, ext)
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) return null

        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
            .header("Accept", "image/avif,image/webp,image/png,image/jpeg,*/*;q=0.8")
        if (!referer.isNullOrBlank() && (referer.startsWith("http://") || referer.startsWith("https://"))) {
            reqBuilder.header("Referer", referer)
        }

        UhHttpClient.client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            val len = body.contentLength()
            if (len > MAX_IMAGE_BYTES) return null
            val bytes = body.bytes()
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
            val contentType = resp.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val ext = detectImageExtension(bytes) ?: guessExtensionFromUrlOrMime(url, contentType, "jpg")
            val mime = contentType.ifBlank { guessMimeType(ext, "image/jpeg") }
            return FetchedMedia(bytes, mime, ext)
        }
    }

    private fun decodeScaledBitmap(bytes: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun detectImageExtension(b: ByteArray): String? {
        if (b.size < 12) return null
        return when {
            b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "jpg"
            b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() -> "png"
            b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "gif"
            b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
                b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte() -> "webp"
            else -> null
        }
    }

    fun guessExtensionFromUrlOrMime(url: String, mime: String?, fallback: String): String {
        val m = mime?.lowercase(Locale.ROOT).orEmpty()
        when {
            m.contains("png") -> return "png"
            m.contains("webp") -> return "webp"
            m.contains("gif") -> return "gif"
            m.contains("avif") -> return "avif"
            m.contains("svg") -> return "svg"
            m.contains("jpeg") || m.contains("jpg") -> return "jpg"
            m.contains("mp4") -> return "mp4"
            m.contains("webm") -> return "webm"
            m.contains("mpeg") && m.startsWith("audio") -> return "mp3"
            m.contains("ogg") -> return "ogg"
            m.contains("wav") -> return "wav"
            m.contains("pdf") -> return "pdf"
        }
        val urlExt = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (urlExt.length in 2..5 && urlExt.all { it.isLetterOrDigit() }) {
            return if (urlExt == "jpeg") "jpg" else urlExt
        }
        return fallback
    }

    fun guessMimeType(ext: String, fallback: String): String {
        val fromMap = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase(Locale.ROOT))
        if (!fromMap.isNullOrBlank()) return fromMap
        return when (ext.lowercase(Locale.ROOT)) {
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "jpg", "jpeg" -> "image/jpeg"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            else -> fallback
        }
    }

    private fun sanitizeFilename(name: String): String {
        return name
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .take(100)
            .ifBlank { "uh_media_${System.currentTimeMillis()}" }
    }
}
