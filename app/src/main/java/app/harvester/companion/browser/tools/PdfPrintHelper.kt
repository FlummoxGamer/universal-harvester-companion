package app.harvester.companion.browser.tools

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import android.widget.Toast
import app.harvester.companion.UHLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object PdfPrintHelper {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun saveSessionAsPdf(context: Context, session: GeckoSession?, suggestedTitle: String) {
        if (session == null) {
            Toast.makeText(context, "No active page to save as PDF", Toast.LENGTH_SHORT).show()
            return
        }
        val appCtx = context.applicationContext
        val safeName = sanitizeFilename(suggestedTitle) + ".pdf"

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

    /**
     * Launches Android's native System Print Preview backed by a custom PrintDocumentAdapter
     * that streams GeckoSession.saveAsPdf() directly into the Print Spooler.
     */
    fun printSession(context: Context, session: GeckoSession?, suggestedTitle: String = "UH Page") {
        if (session == null) {
            Toast.makeText(context, "No active page to print", Toast.LENGTH_SHORT).show()
            return
        }
        val activity = findActivity(context)
        val printManager = (activity ?: context).getSystemService(Context.PRINT_SERVICE) as? PrintManager
        if (printManager == null) {
            Toast.makeText(context, "System Print service unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        val cleanTitle = sanitizeFilename(suggestedTitle)
        val jobName = "$cleanTitle - Print"

        try {
            val adapter = GeckoPdfPrintDocumentAdapter(
                appContext = context.applicationContext,
                session = session,
                fileName = "$cleanTitle.pdf",
            )
            printManager.print(jobName, adapter, PrintAttributes.Builder().build())
        } catch (e: Exception) {
            UHLog.throwable("print", "PrintManager.print failed", e)
            Toast.makeText(context, "Could not open print preview", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sanitizeFilename(raw: String): String {
        return raw
            .replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
            .trim()
            .take(64)
            .ifBlank { "page_${System.currentTimeMillis()}" }
    }

    private fun findActivity(context: Context): Activity? {
        var cur: Context? = context
        while (cur is ContextWrapper) {
            if (cur is Activity) return cur
            cur = cur.baseContext
        }
        return null
    }

    private class GeckoPdfPrintDocumentAdapter(
        private val appContext: Context,
        private val session: GeckoSession,
        private val fileName: String,
    ) : PrintDocumentAdapter() {

        @Volatile
        private var cachedPdfFile: File? = null

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback?,
            extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback?.onLayoutCancelled()
                return
            }

            val existing = cachedPdfFile
            if (existing != null && existing.isFile && existing.length() > 0L) {
                val info = PrintDocumentInfo.Builder(fileName)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                    .build()
                callback?.onLayoutFinished(info, false)
                return
            }

            mainHandler.post {
                if (cancellationSignal?.isCanceled == true) {
                    callback?.onLayoutCancelled()
                    return@post
                }
                try {
                    session.saveAsPdf().accept({ inputStream ->
                        if (inputStream == null) {
                            mainHandler.post { callback?.onLayoutFailed("Could not render page for printing") }
                            return@accept
                        }
                        ioScope.launch {
                            try {
                                val printDir = File(appContext.cacheDir, "uh_print").apply { mkdirs() }
                                val tmp = File(printDir, "print_${System.currentTimeMillis()}.pdf")
                                inputStream.use { input ->
                                    FileOutputStream(tmp).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                if (cancellationSignal?.isCanceled == true) {
                                    tmp.delete()
                                    mainHandler.post { callback?.onLayoutCancelled() }
                                    return@launch
                                }
                                cachedPdfFile?.delete()
                                cachedPdfFile = tmp
                                val info = PrintDocumentInfo.Builder(fileName)
                                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                                    .build()
                                mainHandler.post {
                                    callback?.onLayoutFinished(info, true)
                                }
                            } catch (e: Exception) {
                                UHLog.throwable("print", "Failed staging PDF for print", e)
                                mainHandler.post {
                                    callback?.onLayoutFailed(e.message ?: "Print layout failed")
                                }
                            }
                        }
                    }, { err ->
                        UHLog.w("print", "saveAsPdf failed during print layout: ${err?.message}")
                        mainHandler.post {
                            callback?.onLayoutFailed(err?.message ?: "Could not generate printable document")
                        }
                    })
                } catch (e: Exception) {
                    UHLog.throwable("print", "GeckoSession.saveAsPdf threw during print", e)
                    callback?.onLayoutFailed(e.message ?: "Print unavailable")
                }
            }
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor?,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback?.onWriteCancelled()
                return
            }
            val src = cachedPdfFile
            if (src == null || !src.isFile || destination == null) {
                callback?.onWriteFailed("Printable PDF file unavailable")
                return
            }
            ioScope.launch {
                try {
                    FileInputStream(src).use { input ->
                        FileOutputStream(destination.fileDescriptor).use { output ->
                            input.copyTo(output)
                            output.flush()
                        }
                    }
                    if (cancellationSignal?.isCanceled == true) {
                        mainHandler.post { callback?.onWriteCancelled() }
                    } else {
                        mainHandler.post {
                            callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                        }
                    }
                } catch (e: Exception) {
                    UHLog.throwable("print", "Failed writing PDF bytes to Print Spooler", e)
                    mainHandler.post {
                        callback?.onWriteFailed(e.message ?: "Print write error")
                    }
                }
            }
        }

        override fun onFinish() {
            super.onFinish()
            ioScope.launch {
                try {
                    cachedPdfFile?.delete()
                    cachedPdfFile = null
                } catch (_: Exception) {}
            }
        }
    }
}
