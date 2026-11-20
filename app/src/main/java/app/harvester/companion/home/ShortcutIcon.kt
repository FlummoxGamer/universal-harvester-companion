package app.harvester.companion.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlin.math.abs

@Composable
fun ShortcutIcon(
    iconUrl: String,
    pageUrl: String = "",
    label: String = "",
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cacheTick by ShortcutIconCache.versionTick.collectAsState()

    // Synchronous Frame-1 lookup from in-memory LruCache / persistent pre-baked PNG
    // so Home widgets render instantaneously with zero monogram flash or reload animation.
    var immediateBitmap by remember(pageUrl, cacheTick) {
        mutableStateOf<ImageBitmap?>(
            if (pageUrl.isNotBlank()) ShortcutIconCache.getImmediateImageBitmap(context, pageUrl) else null,
        )
    }

    // Silent background freshness check (once per day) & first-time high-res fetch
    LaunchedEffect(pageUrl, iconUrl) {
        if (pageUrl.isNotBlank()) {
            val resolved = ShortcutIconCache.resolveIcon(context, pageUrl, iconUrl)
            if (resolved != null && resolved.exists()) {
                val refreshed = ShortcutIconCache.getImmediateImageBitmap(context, pageUrl)
                if (refreshed != null) {
                    immediateBitmap = refreshed
                }
            }
        }
    }

    val readyBitmap = immediateBitmap
    when {
        readyBitmap != null -> {
            Image(
                bitmap = readyBitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
                modifier = modifier.padding(6.dp),
            )
        }
        pageUrl.isBlank() && iconUrl.isNotBlank() -> {
            val req = remember(context, iconUrl) {
                ImageRequest.Builder(context)
                    .data(iconUrl)
                    .transformations(TransparentCornerTransformation())
                    .crossfade(false)
                    .build()
            }
            SubcomposeAsyncImage(
                model = req,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = modifier.padding(6.dp),
                loading = {
                    MonogramFallback(label = label, pageUrl = pageUrl, modifier = Modifier.fillMaxSize())
                },
                error = {
                    MonogramFallback(label = label, pageUrl = pageUrl, modifier = Modifier.fillMaxSize())
                },
            )
        }
        else -> {
            MonogramFallback(label = label, pageUrl = pageUrl, modifier = modifier)
        }
    }
}

@Composable
private fun MonogramFallback(
    label: String,
    pageUrl: String,
    modifier: Modifier = Modifier,
) {
    val seed = (pageUrl.ifBlank { label }).hashCode()
    val palette = remember(seed) {
        val hues = listOf(
            Pair(Color(0xFF4F46E5), Color(0xFF7C3AED)),
            Pair(Color(0xFF0284C7), Color(0xFF2563EB)),
            Pair(Color(0xFF059669), Color(0xFF0D9488)),
            Pair(Color(0xFFD97706), Color(0xFFEA580C)),
            Pair(Color(0xFFDB2777), Color(0xFFE11D48)),
            Pair(Color(0xFF7C3AED), Color(0xFFA855F7)),
        )
        hues[abs(seed) % hues.size]
    }
    val initial = remember(label, pageUrl) {
        val raw = label.trim().ifBlank {
            pageUrl.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        }
        raw.firstOrNull()?.uppercaseChar()?.toString() ?: "W"
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(palette.first, palette.second))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
