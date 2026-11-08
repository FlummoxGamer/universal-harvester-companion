package app.harvester.companion.browser.menu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import app.harvester.companion.R
import app.harvester.companion.UHLog
import mozilla.components.concept.engine.HitResult
import mozilla.components.feature.tabs.TabsUseCases

@Composable
fun ContextMenuDialog(
    hitResult: HitResult,
    tabsUseCases: TabsUseCases,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colorResource(id = R.color.surface_2),
            modifier = Modifier.width(320.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                var imageUrl = ""
                var linkUrl = ""

                if (hitResult is HitResult.IMAGE) imageUrl = hitResult.src
                if (hitResult is HitResult.IMAGE_SRC) imageUrl = hitResult.src
                if (hitResult is HitResult.UNKNOWN) linkUrl = hitResult.src

                if (imageUrl.isNotEmpty()) {
                    val rawName = imageUrl.substringAfterLast("/").substringBefore("?")
                    val displayName = if (rawName.isNotBlank()) rawName else imageUrl

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = "Image Preview",
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black)
                        )
                        Text(
                            text = displayName,
                            color = colorResource(id = R.color.text),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                    
                    MenuItem("Save Image") {
                        onDismiss()
                        val filename = "image_${System.currentTimeMillis()}.jpg"
                        app.harvester.companion.DownloadEngine.startDownload(context, imageUrl, filename, "image/jpeg", null, null, null)
                    }
                    MenuItem("Copy Image") {
                        onDismiss()
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Image URL", imageUrl))
                    }
                    MenuItem("Search with Lens") {
                        onDismiss()
                        try {
                            val uri = Uri.parse("https://lens.google.com/uploadbyurl?url=$imageUrl")
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (e: Exception) {
                            UHLog.e("context", "Lens failed: ${e.message}")
                        }
                    }
                    MenuItem("Extract Text") {
                        onDismiss()
                        extractTextFromImage(context, imageUrl)
                    }
                    MenuItem("Open in New Tab") {
                        onDismiss()
                        tabsUseCases.addTab(imageUrl)
                    }
                    MenuItem("Copy Link") {
                        onDismiss()
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Image URL", imageUrl))
                    }
                } else if (linkUrl.isNotEmpty() && linkUrl.startsWith("http")) {
                    MenuItem("Open Link in New Tab") {
                        onDismiss()
                        tabsUseCases.addTab(linkUrl)
                    }
                    MenuItem("Copy Link") {
                        onDismiss()
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Link URL", linkUrl))
                    }
                    MenuItem("Share Link") {
                        onDismiss()
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, linkUrl)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share Link"))
                    }
                } else {
                    MenuItem("Close") { onDismiss() }
                }
            }
        }
    }
}

private fun extractTextFromImage(context: Context, imageUrl: String) {
    Toast.makeText(context, "Scanning image...", Toast.LENGTH_SHORT).show()
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
        val image = InputImage.fromFilePath(context, Uri.parse(imageUrl))
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                if (visionText.text.isNotBlank()) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Extracted Text", visionText.text))
                    Toast.makeText(context, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "No text found", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                UHLog.e("context", "OCR Failed: ${e.message}")
                Toast.makeText(context, "Failed to read text", Toast.LENGTH_SHORT).show()
            }
    } catch (e: Exception) {
        UHLog.e("context", "OCR Error: ${e.message}")
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = colorResource(id = R.color.text),
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp)
    )
}
