package app.harvester.companion.browser.tools

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import app.harvester.companion.UHLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession
import java.io.File

object PdfPrintHelper {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun saveSessionAsPdf(context: Context, session: GeckoSession?, suggestedTitle: String) {
        if (session == null) {
            Toast.makeText(context, "No active page to save as PDF", Toast.LENGTH_SHORT).show()
            return
        }
        val appCtx = context.applicationContext
        val safeName = suggestedTitle
            .replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
            .trim()
            .take(64)
            .ifBlank { "page_${System.currentTimeMillis()}" } + ".pdf"

        Toast.makeText(appCtx, "Generating PDF…", Toast.LENGTH_SHORT).show()

        try {
            session.saveAsPdf().accept({ inputStream ->
                if (inputStream == null) {
                    mainHandler.post {
                        Toast.makeText(appCtx, "Could not generate PDF", Toast.LENGTH_SHORT).show()
                    }
                    return@accept
                }
                ioScope.launch {
                    try {
                        inputStream.use { stream ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val values = ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                                }
                                val uri = appCtx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                    ?: throw IllegalStateException("Could not create Downloads entry")
                                appCtx.contentResolver.openOutputStream(uri)?.use { out ->
                                    stream.copyTo(out)
                                }
                            } else {
                                @Suppress("DEPRECATION")
                                val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                dlDir.mkdirs()
                                File(dlDir, safeName).outputStream().use { out ->
                                    stream.copyTo(out)
                                }
                            }
                        }
                        mainHandler.post {
                            Toast.makeText(appCtx, "Saved PDF to Downloads: $safeName", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        UHLog.throwable("pdf", "Failed writing PDF", e)
                        mainHandler.post {
                            Toast.makeText(appCtx, "Failed saving PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }, { err ->
                UHLog.w("pdf", "GeckoSession.saveAsPdf error: ${err?.message}")
                mainHandler.post {
                    Toast.makeText(appCtx, "Could not export page to PDF", Toast.LENGTH_SHORT).show()
                }
            })
        } catch (e: Exception) {
            UHLog.throwable("pdf", "saveAsPdf invocation failed", e)
            Toast.makeText(appCtx, "PDF export unavailable on this page", Toast.LENGTH_SHORT).show()
        }
    }

    fun printSession(context: Context, session: GeckoSession?) {
        if (session == null) {
            Toast.makeText(context, "No active page to print", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            session.printPageContent()
        } catch (e: Exception) {
            UHLog.throwable("print", "printPageContent failed", e)
            Toast.makeText(context, "Printing unavailable on this page", Toast.LENGTH_SHORT).show()
        }
    }
}
