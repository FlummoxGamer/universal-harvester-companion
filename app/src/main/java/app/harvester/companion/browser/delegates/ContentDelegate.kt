package app.harvester.companion.browser.delegates

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.widget.Toast
import mozilla.components.browser.state.action.DownloadAction
import mozilla.components.browser.state.state.content.DownloadState
import app.harvester.companion.UHLog
import app.harvester.companion.components.Components
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.store.BrowserStore
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse

class ContentDelegate(
    private val context: Context,
    private val components: Components,
    private val store: BrowserStore
) : GeckoSession.ContentDelegate {

    override fun onFirstComposite(session: GeckoSession) {}
    override fun onTitleChange(session: GeckoSession, title: String?) {}

    override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
        val headers = response.headers
        val disposition = headers["Content-Disposition"] ?: headers["content-disposition"]
        val contentType = headers["Content-Type"] ?: headers["content-type"]
        val cleanContentType = contentType?.lowercase()?.trim() ?: ""

        val isHtml = cleanContentType.contains("text/html") || cleanContentType.contains("application/xhtml+xml")
        val isAttachment = disposition?.contains("attachment", ignoreCase = true) == true
        val hasFilename = disposition?.let { Regex("""filename\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: false
        if (isHtml && !isAttachment && !hasFilename) {
            return
        }

        val filename = extractFilename(disposition) ?: "download_${System.currentTimeMillis()}"
        val userAgent = headers["User-Agent"] ?: headers["user-agent"]
        val body = response.body
        val contentLength = headers["Content-Length"]?.toLongOrNull() ?: headers["content-length"]?.toLongOrNull() ?: -1L
        val currentUrl = store.state.selectedTab?.content?.url ?: ""

        val downloadState = mozilla.components.browser.state.state.content.DownloadState(
            url = response.uri,
            fileName = filename,
            contentType = contentType ?: "application/octet-stream",
            contentLength = contentLength,
            userAgent = userAgent,
            referrerUrl = currentUrl,
            directoryPath = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).path
        )
        components.store.dispatch(mozilla.components.browser.state.action.DownloadAction.AddDownloadAction(downloadState))
    }

    override fun onCrash(session: GeckoSession) {
        UHLog.e("gecko", "Tab crashed. Reloading.")
        session.reload()
    }

    override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, displayContext: GeckoSession.ContentDelegate.ContextElement) {
        if (displayContext.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE) {
            val imageUrl = displayContext.srcUri
            if (!imageUrl.isNullOrBlank()) {
                AlertDialog.Builder(context)
                    .setTitle("Image Options")
                    .setItems(arrayOf("Download Image", "Share Image", "Copy Image Link")) { _, which ->
                        when (which) {
                            0 -> {
                                val filename = "image_${System.currentTimeMillis()}.jpg"
                                val currentUrl = store.state.selectedTab?.content?.url ?: ""
                                if (imageUrl.startsWith("blob:") || imageUrl.startsWith("data:")) {
                                    val js = "javascript:fetch('$imageUrl').then(r=>r.blob()).then(b=>{var reader=new FileReader();reader.onload=function(){window.postMessage({type:'UH_NATIVE_CALL',payload:{type:'SAVE_BLOB',filename:'$filename',mime:b.type,base64:reader.result.split(',')[1]}},'*')};reader.readAsDataURL(b);})"
                                    components.useCases.sessionUseCases.loadUrl(js)
                                } else {
                                    DownloadEngine.startDownload(context, imageUrl, filename, "image/jpeg", null, null, currentUrl)
                                }
                            }
                            1 -> {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, imageUrl)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share Image Link"))
                            }
                            2 -> {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("Image URL", imageUrl))
                                Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .show()
            }
        }
    }

    private fun extractFilename(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        val rx = Regex("""filename[^;=\n]*=((['"]).*?\2|[^;\n]*)""", RegexOption.IGNORE_CASE)
        val m = rx.find(contentDisposition) ?: return null
        return m.groupValues[1].trim().trim('"', '\'')
    }
}
