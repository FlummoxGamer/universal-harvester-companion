package app.harvester.companion

import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.browser.menu.ContextMenuActions
import app.harvester.companion.browser.menu.OcrResultDialog
import app.harvester.companion.core.network.UhHttpClient
import app.harvester.companion.theme.UHTheme
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch

/**
 * Fullscreen Material 3 Compose Image Viewer with pinch-to-zoom, double-tap zoom,
 * pan gestures, and quick Download / Copy Image / Share / OCR actions.
 */
class ImagePreviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        val imageUrl = intent.getStringExtra("url")
        val referer = intent.getStringExtra("referer")
        val title = intent.getStringExtra("title")

        if (imageUrl.isNullOrBlank()) {
            finish()
            return
        }

        setContent {
            UHTheme(
                lightStatusBarOverride = false,
                lightNavigationBarOverride = false,
            ) {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                var scale by remember { mutableFloatStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                var showChrome by remember { mutableStateOf(true) }
                var ocrText by remember { mutableStateOf<String?>(null) }
                var isBusy by remember { mutableStateOf(false) }

                val displayName = remember(imageUrl, title) {
                    ContextMenuActions.resolveDisplayFilename(imageUrl, title, "image", "jpg")
                }

                val imageRequest = remember(imageUrl, referer) {
                    ImageRequest.Builder(context)
                        .data(imageUrl)
                        .addHeader("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                        .apply {
                            if (!referer.isNullOrBlank() && referer.startsWith("http")) {
                                addHeader("Referer", referer)
                            }
                        }
                        .crossfade(true)
                        .build()
                }

                if (ocrText != null) {
                    OcrResultDialog(
                        extractedText = ocrText.orEmpty(),
                        onSearchWeb = { query ->
                            val components = (context.applicationContext as UHApp).components
                            val searchUrl = SearchEnginePrefs.getSearchUrl(context, query)
                            components.useCases.tabsUseCases.addTab(searchUrl, selectTab = true)
                            finish()
                        },
                        onDismiss = { ocrText = null },
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { showChrome = !showChrome },
                                onDoubleTap = {
                                    if (scale > 1.05f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                },
                            )
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val nextScale = (scale * zoom).coerceIn(1f, 5f)
                                scale = nextScale
                                offset = if (nextScale <= 1.01f) {
                                    Offset.Zero
                                } else {
                                    offset + pan
                                }
                            }
                        },
                ) {
                    SubcomposeAsyncImage(
                        model = imageRequest,
                        contentDescription = displayName,
                        contentScale = ContentScale.Fit,
                        loading = {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp)
                            }
                        },
                        error = {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Failed to load image", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                    )

                    if (showChrome) {
                        // Top bar
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.55f))
                                .statusBarsPadding()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            IconButton(
                                onClick = { finish() },
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.14f)),
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.fx_ic_close_24),
                                    contentDescription = "Close",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Text(
                                text = displayName,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        // Floating Bottom Action Bar
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = Color(0xCC16161D),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = 18.dp, start = 20.dp, end = 20.dp)
                                .border(0.7.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(24.dp)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    enabled = !isBusy,
                                    onClick = {
                                        ContextMenuActions.enqueueSmartDownload(
                                            context = context,
                                            url = imageUrl,
                                            title = title,
                                            fallbackPrefix = "image",
                                            defaultMime = "image/jpeg",
                                            defaultExt = "jpg",
                                        )
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.fx_ic_downloads_24),
                                        contentDescription = "Download image",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                IconButton(
                                    enabled = !isBusy,
                                    onClick = {
                                        isBusy = true
                                        scope.launch {
                                            ContextMenuActions.copyImageBitmapToClipboard(context, imageUrl, referer)
                                            isBusy = false
                                        }
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.uh_ic_copy_24),
                                        contentDescription = "Copy image",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                IconButton(
                                    enabled = !isBusy,
                                    onClick = {
                                        isBusy = true
                                        scope.launch {
                                            val res = ContextMenuActions.extractTextFromImage(imageUrl, referer)
                                            isBusy = false
                                            val txt = res.getOrNull().orEmpty()
                                            if (txt.isNotBlank()) ocrText = txt
                                            else Toast.makeText(context, "No text found", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.uh_ic_translate_24),
                                        contentDescription = "Extract text",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                IconButton(
                                    enabled = !isBusy,
                                    onClick = {
                                        isBusy = true
                                        scope.launch {
                                            ContextMenuActions.shareImageFile(context, imageUrl, referer)
                                            isBusy = false
                                        }
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.uh_ic_share_24),
                                        contentDescription = "Share image",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
