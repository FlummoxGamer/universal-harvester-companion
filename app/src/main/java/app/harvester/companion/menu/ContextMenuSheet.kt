package app.harvester.companion.browser.menu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.UHLog
import mozilla.components.concept.engine.HitResult
import mozilla.components.feature.tabs.TabsUseCases

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextMenuSheet(
    hitResult: HitResult,
    tabsUseCases: TabsUseCases,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colorResource(id = R.color.surface_2)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            when (hitResult) {
                is HitResult.IMAGE, is HitResult.IMAGE_SRC -> {
                    val imageUrl = hitResult.src
                    if (imageUrl != null) {
                        MenuItem("Save Image") {
                            onDismiss()
                            val filename = "image_${System.currentTimeMillis()}.jpg"
                            app.harvester.companion.DownloadEngine.startDownload(context, imageUrl, filename, "image/jpeg", null, null, null)
                        }
                        MenuItem("Share Image") {
                            onDismiss()
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, imageUrl)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Image"))
                        }
                        MenuItem("Open Image in New Tab") {
                            onDismiss()
                            tabsUseCases.addTab(imageUrl)
                        }
                        MenuItem("Copy Image Link") {
                            onDismiss()
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Image URL", imageUrl))
                        }
                    }
                }
                is HitResult.LINK -> {
                    val linkUrl = hitResult.href
                    if (linkUrl != null) {
                        MenuItem("Open Link in New Tab") {
                            onDismiss()
                            tabsUseCases.addTab(linkUrl)
                        }
                        MenuItem("Copy Link Address") {
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
                    }
                }
                else -> {
                    MenuItem("Close") { onDismiss() }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = colorResource(id = R.color.text),
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 16.dp)
    )
}
