package app.harvester.companion.browser.webapp

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.ShortcutStore
import mozilla.components.concept.engine.manifest.WebAppManifest
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallWebAppDialog(
    url: String,
    pageTitle: String,
    tabIcon: Bitmap?,
    manifest: WebAppManifest?,
    onAddToUhHomeGrid: (title: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isPwa = remember(manifest) { WebAppStore.isPwaInstallable(manifest) }

    val host = remember(url) {
        try {
            URI(url).host?.removePrefix("www.").orEmpty().ifBlank { url }
        } catch (_: Exception) {
            url
        }
    }

    val initialLabel = remember(url, pageTitle, manifest) {
        if (isPwa) {
            manifest?.shortName?.ifBlank { manifest.name }
                ?: pageTitle.ifBlank { ShortcutStore.shortNameFor(url) }
        } else {
            pageTitle.ifBlank { ShortcutStore.shortNameFor(url) }
        }
    }

    var label by remember(initialLabel) { mutableStateOf(initialLabel) }
    var resolvedBitmap by remember(url, manifest) { mutableStateOf<Bitmap?>(tabIcon) }
    var isResolvingIcon by remember(url, manifest) { mutableStateOf(true) }

    LaunchedEffect(url, tabIcon, manifest) {
        isResolvingIcon = true
        resolvedBitmap = WebAppStore.resolveSiteIconBitmap(
            context = context,
            pageUrl = url,
            tabIcon = tabIcon,
            manifest = manifest,
        )
        isResolvingIcon = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val bmp = resolvedBitmap
                        if (bmp != null && !bmp.isRecycled) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = label,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                        } else if (isResolvingIcon) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(22.dp)
                            )
                        } else {
                            Icon(
                                painter = painterResource(id = R.drawable.uh_ic_globe_24),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isPwa) "Install Web App" else "Add to Home Screen",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = host,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (isPwa) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Installs as a standalone app with its own icon and full-screen experience.",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(if (isPwa) "App name" else "Shortcut name") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            // Primary action: Install PWA or Pin real-favicon shortcut to Android Home Screen
            Button(
                onClick = {
                    val bmp = resolvedBitmap ?: tabIcon
                    if (bmp != null) {
                        WebAppStore.pinWebAppOrShortcut(
                            activityContext = context,
                            pageUrl = url,
                            title = label,
                            iconBitmap = bmp,
                            manifest = manifest,
                        )
                        onDismiss()
                    }
                },
                enabled = resolvedBitmap != null,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = if (isPwa) "Install App" else "Add to Android Home Screen",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Secondary action: Add to UH Browser Home Grid
            OutlinedButton(
                onClick = {
                    onAddToUhHomeGrid(label)
                    onDismiss()
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                Text(
                    text = "Add to UH Home Grid",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
