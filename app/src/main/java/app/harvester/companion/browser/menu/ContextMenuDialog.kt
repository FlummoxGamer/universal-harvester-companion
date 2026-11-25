package app.harvester.companion.browser.menu

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.harvester.companion.R
import app.harvester.companion.core.network.UhHttpClient
import app.harvester.companion.privacy.UhShieldEngine
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import mozilla.components.concept.engine.HitResult
import mozilla.components.feature.tabs.TabsUseCases

/**
 * Centered floating Material 3 Long-Press Context Menu card with modern mobile browser
 * actions across Image, Linked Image (dual actions), Link, Video, Audio, Email, Phone & Geo.
 */
@Composable
fun ContextMenuDialog(
    hitResult: HitResult,
    pageUrl: String?,
    isPrivate: Boolean,
    tabsUseCases: TabsUseCases,
    onDismiss: () -> Unit,
    onOcrResult: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busyLabel by remember { mutableStateOf<String?>(null) }

    val imageUrl: String? = when (hitResult) {
        is HitResult.IMAGE -> hitResult.src
        is HitResult.IMAGE_SRC -> hitResult.src
        else -> null
    }
    val linkUrl: String? = when (hitResult) {
        is HitResult.IMAGE_SRC -> hitResult.uri
        is HitResult.UNKNOWN -> hitResult.src.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        else -> null
    }
    val videoUrl: String? = (hitResult as? HitResult.VIDEO)?.src
    val audioUrl: String? = (hitResult as? HitResult.AUDIO)?.src
    val emailValue: String? = (hitResult as? HitResult.EMAIL)?.src
    val phoneValue: String? = (hitResult as? HitResult.PHONE)?.src
    val geoValue: String? = (hitResult as? HitResult.GEO)?.src

    val titleHint: String? = when (hitResult) {
        is HitResult.IMAGE -> hitResult.title
        is HitResult.VIDEO -> hitResult.title
        is HitResult.AUDIO -> hitResult.title
        else -> null
    }

    Dialog(
        onDismissRequest = { if (busyLabel == null) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 16.dp,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 28.dp)
                .fillMaxWidth()
                .widthIn(max = 380.dp)
                .heightIn(max = 600.dp)
                .border(
                    width = 0.8.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(24.dp),
                ),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Rich Preview Header
                ContextMenuHeader(
                    imageUrl = imageUrl,
                    linkUrl = linkUrl,
                    videoUrl = videoUrl,
                    audioUrl = audioUrl,
                    emailValue = emailValue,
                    phoneValue = phoneValue,
                    geoValue = geoValue,
                    titleHint = titleHint,
                    referer = pageUrl,
                )

                HorizontalDivider(
                    thickness = 0.6.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )

                if (busyLabel != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.4.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = busyLabel.orEmpty(),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 6.dp),
                    ) {
                        // --- LINK SECTION (for UNKNOWN and IMAGE_SRC) ---
                        if (!linkUrl.isNullOrBlank()) {
                            if (!imageUrl.isNullOrBlank()) {
                                SectionLabel("LINK")
                            }
                            ContextActionRow(
                                iconRes = R.drawable.uh_ic_tab_modern_24,
                                label = "Open in new tab",
                                onClick = {
                                    onDismiss()
                                    tabsUseCases.addTab(url = linkUrl, selectTab = true, private = isPrivate)
                                },
                            )
                            ContextActionRow(
                                iconRes = R.drawable.uh_ic_plus_24,
                                label = "Open in background tab",
                                onClick = {
                                    onDismiss()
                                    tabsUseCases.addTab(url = linkUrl, selectTab = false, private = isPrivate)
                                    Toast.makeText(context, "Opened in background tab", Toast.LENGTH_SHORT).show()
                                },
                            )
                            if (!isPrivate) {
                                ContextActionRow(
                                    iconRes = R.drawable.uh_ic_private_mode_24,
                                    label = "Open in Private tab",
                                    onClick = {
                                        onDismiss()
                                        tabsUseCases.addTab(url = linkUrl, selectTab = true, private = true)
                                    },
                                )
                            }
                            ContextActionRow(
                                iconRes = R.drawable.uh_ic_copy_24,
                                label = "Copy link address",
                                onClick = {
                                    onDismiss()
                                    val clean = UhShieldEngine.cleanTrackingUrl(linkUrl, stripTrackers = true, deAmp = true)
                                    ContextMenuActions.copyText(context, "Link URL", clean, "Link copied")
                                },
                            )
                            ContextActionRow(
                                iconRes = R.drawable.uh_ic_share_24,
                                label = "Share link",
                                onClick = {
                                    onDismiss()
                                    ContextMenuActions.shareText(context, linkUrl, "Share link")
                                },
                            )
                            if (imageUrl.isNullOrBlank()) {
                                ContextActionRow(
                                    iconRes = R.drawable.fx_ic_downloads_24,
                                    label = "Download link",
                                    onClick = {
                                        onDismiss()
                                        ContextMenuActions.enqueueSmartDownload(
                                            context = context,
                                            url = linkUrl,
                                            fallbackPrefix = "download",
                                            defaultMime = "application/octet-stream",
                                            defaultExt = "bin",
                                            isPrivate = isPrivate,
                                        )
                                    },
                                )
                            }
                        }

                        // --- IMAGE SECTION (for IMAGE and IMAGE_SRC) ---
                        if (!imageUrl.isNullOrBlank()) {
                            if (!linkUrl.isNullOrBlank()) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    thickness = 0.6.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                )
                                SectionLabel("IMAGE")
                            }

                            ContextActionRow(
                                iconRes = R.drawable.ic_image_frame,
                                label = "Preview image fullscreen",
                                onClick = {
                                    onDismiss()
                                    ContextMenuActions.openFullscreenPreview(context, imageUrl, pageUrl, titleHint)
                                },
                            )
                            ContextActionRow(
                                iconRes = R.drawable.fx_ic_downloads_24,
                                label = "Download image",
                                onClick = {
                                    onDismiss()
                                    ContextMenuActions.enqueueSmartDownload(
                                        context = context,
                                        url = imageUrl,
                                        title = titleHint,
                                        fallbackPrefix = "image",
                                        defaultMime = "image/jpeg",
                                        defaultExt = "jpg",
                                        isPrivate = isPrivate,
                                    )
                                },
                            )
                            ContextActionRow(
                                iconRes = R.drawable.uh_ic_copy_24,
                                label = "Copy image",
                                onClick = {
                                    busyLabel = "Copying image..."
                                    scope.launch {
                                        ContextMenuActions.copyImageBitmapToClipboard(context, imageUrl, pageUrl)
                                        busyLabel = null
                                        onDismiss()
                                    }
                                },
                            )
                            ContextActionRow(
                                iconRes = R.drawable.uh_ic_link_24,
                                label = "Copy image link",
                                onClick = {
                                    onDismiss()
                                    ContextMenuActions.copyText(context, "Image URL", imageUrl, "Image link copied")
                                },
                            )
                            if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
                                ContextActionRow(
                                    iconRes = R.drawable.uh_ic_google_lens_24,
                                    label = "Search image with Google Lens",
                                    onClick = {
                                        onDismiss()
                                        tabsUseCases.addTab(
                                            url = ContextMenuActions.buildGoogleLensUrl(imageUrl),
                                            selectTab = true,
                                            private = isPrivate,
                                        )
                                    },
                                )
                            }
                            ContextActionRow(
                                iconRes = R.drawable.uh_ic_translate_24,
                                label = "Extract text from image (OCR)",
                                onClick = {
                                    busyLabel = "Scanning text in image..."
                                    scope.launch {
                                        val res = ContextMenuActions.extractTextFromImage(imageUrl, pageUrl)
                                        busyLabel = null
                                        val text = res.getOrNull().orEmpty()
                                        if (text.isNotBlank()) {
                                            onDismiss()
                                            onOcrResult(text)
                                        } else {
                                            Toast.makeText(context, "No readable text found in image", Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        }
                                    }
                                },
                            )
                            ContextActionRow(
                                iconRes = R.drawable.uh_ic_tab_modern_24,
                                label = "Open image in new tab",
                                onClick = {
                                    onDismiss()
                                    tabsUseCases.addTab(url = imageUrl, selectTab = true, private = isPrivate)
                                },
                            )
                            ContextActionRow(
                                iconRes = R.drawable.uh_ic_plus_24,
                                label = "Open image in background tab",
                                onClick = {
                                    onDismiss()
                                    tabsUseCases.addTab(url = imageUrl, selectTab = false, private = isPrivate)
                                    Toast.makeText(context, "Image opened in background tab", Toast.LENGTH_SHORT).show()
                                },
                            )
                            ContextActionRow(
                                iconRes = R.drawable.uh_ic_share_24,
                                label = "Share image",
                                onClick = {
                                    busyLabel = "Preparing image to share..."
                                    scope.launch {
                                        ContextMenuActions.shareImageFile(context, imageUrl, pageUrl)
                                        busyLabel = null
                                        onDismiss()
                                    }
                                },
                            )
                        }

                        // --- VIDEO / AUDIO SECTION ---
                        val mediaUrl = videoUrl ?: audioUrl
                        if (!mediaUrl.isNullOrBlank()) {
                            val isVideo = videoUrl != null
                            val mediaWord = if (isVideo) "video" else "audio"
                            ContextActionRow(
                                iconRes = R.drawable.fx_ic_downloads_24,
                                label = "Download $mediaWord",
                                onClick = {
                                    onDismiss()
                                    ContextMenuActions.enqueueSmartDownload(
                                        context = context,
                                        url = mediaUrl,
                                        title = titleHint,
                                        fallbackPrefix = mediaWord,
                                        defaultMime = if (isVideo) "video/mp4" else "audio/mpeg",
                                        defaultExt = if (isVideo) "mp4" else "mp3",
                                        isPrivate = isPrivate,
                                    )
                                },
                            )
                            ContextActionRow(
                                iconRes = R.drawable.uh_ic_tab_modern_24,
                                label = "Open $mediaWord in new tab",
                                onClick = {
                                    onDismiss()
                                    tabsUseCases.addTab(url = mediaUrl, selectTab = true, private = isPrivate)
                                },
                            )
                            ContextActionRow(
                                iconRes = R.drawable.uh_ic_copy_24,
                                label = "Copy $mediaWord link",
                                onClick = {
                                    onDismiss()
                                    ContextMenuActions.copyText(context, "Media URL", mediaUrl, "Media link copied")
                                },
                            )
                            ContextActionRow(
                                iconRes = R.drawable.uh_ic_share_24,
                                label = "Share $mediaWord link",
                                onClick = {
                                    onDismiss()
                                    ContextMenuActions.shareText(context, mediaUrl, "Share $mediaWord")
                                },
                            )
                        }

                        // --- EMAIL / PHONE / GEO ---
                        if (!emailValue.isNullOrBlank()) {
                            val addr = emailValue.removePrefix("mailto:")
                            ContextActionRow(
                                iconRes = R.drawable.uh_ic_open_in_app_24,
                                label = "Send email",
                                onClick = {
                                    onDismiss()
                                    runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$addr"))) }
                                },
                            )
                            ContextActionRow(
                                iconRes = R.drawable.uh_ic_copy_24,
                                label = "Copy email address",
                                onClick = {
                                    onDismiss()
                                    ContextMenuActions.copyText(context, "Email", addr, "Email copied")
                                },
                            )
                        }
                        if (!phoneValue.isNullOrBlank()) {
                            val num = phoneValue.removePrefix("tel:")
                            ContextActionRow(
                                iconRes = R.drawable.uh_ic_open_in_app_24,
                                label = "Call number",
                                onClick = {
                                    onDismiss()
                                    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num"))) }
                                },
                            )
                            ContextActionRow(
                                iconRes = R.drawable.uh_ic_copy_24,
                                label = "Copy phone number",
                                onClick = {
                                    onDismiss()
                                    ContextMenuActions.copyText(context, "Phone", num, "Phone number copied")
                                },
                            )
                        }
                        if (!geoValue.isNullOrBlank()) {
                            ContextActionRow(
                                iconRes = R.drawable.uh_ic_location_24,
                                label = "Open in Maps",
                                onClick = {
                                    onDismiss()
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(geoValue))) }
                                },
                            )
                            ContextActionRow(
                                iconRes = R.drawable.uh_ic_copy_24,
                                label = "Copy location",
                                onClick = {
                                    onDismiss()
                                    ContextMenuActions.copyText(context, "Location", geoValue, "Location copied")
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextMenuHeader(
    imageUrl: String?,
    linkUrl: String?,
    videoUrl: String?,
    audioUrl: String?,
    emailValue: String?,
    phoneValue: String?,
    geoValue: String?,
    titleHint: String?,
    referer: String?,
) {
    val context = LocalContext.current
    val primaryUrl = linkUrl ?: imageUrl ?: videoUrl ?: audioUrl ?: emailValue ?: phoneValue ?: geoValue ?: ""
    val headerTitle = when {
        !imageUrl.isNullOrBlank() -> ContextMenuActions.resolveDisplayFilename(imageUrl, titleHint, "image", "jpg")
        !videoUrl.isNullOrBlank() -> ContextMenuActions.resolveDisplayFilename(videoUrl, titleHint, "video", "mp4")
        !audioUrl.isNullOrBlank() -> ContextMenuActions.resolveDisplayFilename(audioUrl, titleHint, "audio", "mp3")
        !linkUrl.isNullOrBlank() -> ContextMenuActions.extractHostLabel(linkUrl)
        !emailValue.isNullOrBlank() -> emailValue.removePrefix("mailto:")
        !phoneValue.isNullOrBlank() -> phoneValue.removePrefix("tel:")
        !geoValue.isNullOrBlank() -> geoValue.removePrefix("geo:")
        else -> "Page Element"
    }
    val subtitle = when {
        !linkUrl.isNullOrBlank() -> linkUrl
        !imageUrl.isNullOrBlank() -> ContextMenuActions.extractHostLabel(imageUrl)
        !videoUrl.isNullOrBlank() -> ContextMenuActions.extractHostLabel(videoUrl)
        !audioUrl.isNullOrBlank() -> ContextMenuActions.extractHostLabel(audioUrl)
        else -> primaryUrl
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!imageUrl.isNullOrBlank()) {
            val req = remember(imageUrl, referer) {
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
            AsyncImage(
                model = req,
                contentDescription = "Preview thumbnail",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(
                        0.6.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(12.dp),
                    ),
            )
        } else {
            val badgeIcon = when {
                !videoUrl.isNullOrBlank() -> R.drawable.fx_ic_file_video_24
                !audioUrl.isNullOrBlank() -> R.drawable.fx_ic_file_audio_24
                !geoValue.isNullOrBlank() -> R.drawable.uh_ic_location_24
                else -> R.drawable.uh_ic_globe_24
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = badgeIcon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = headerTitle,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank() && subtitle != headerTitle) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 18.dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun ContextActionRow(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Clean Material 3 dialog displaying text extracted via ML Kit OCR with select, copy, and web search.
 */
@Composable
fun OcrResultDialog(
    extractedText: String,
    onSearchWeb: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Extracted Text",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = extractedText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    ContextMenuActions.copyText(context, "Extracted Text", extractedText, "Extracted text copied")
                    onDismiss()
                },
            ) {
                Text("Copy All")
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        onSearchWeb(extractedText.take(240))
                        onDismiss()
                    },
                ) {
                    Text("Search")
                }
                TextButton(onClick = onDismiss) {
                    Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(22.dp),
    )
}
