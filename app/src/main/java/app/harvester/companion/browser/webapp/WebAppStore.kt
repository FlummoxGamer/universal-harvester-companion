package app.harvester.companion.browser.webapp

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.harvester.companion.HomeActivity
import app.harvester.companion.ShortcutStore
import app.harvester.companion.UHLog
import app.harvester.companion.core.network.UhHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.concept.engine.manifest.WebAppManifest
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import kotlin.math.abs

data class InstalledWebApp(
    val id: String,
    val startUrl: String,
    val scope: String,
    val name: String,
    val shortName: String,
    val displayMode: String,
    val themeColor: Int?,
    val backgroundColor: Int?,
    val iconPath: String?,
)

object WebAppStore {

    private const val PREFS_NAME = "uh_webapps"
    private const val KEY_ITEMS = "installed_webapps"

    fun isPwaInstallable(manifest: WebAppManifest?): Boolean {
        if (manifest == null) return false
        if (manifest.startUrl.isBlank()) return false
        return manifest.display == WebAppManifest.DisplayMode.STANDALONE ||
            manifest.display == WebAppManifest.DisplayMode.FULLSCREEN ||
            manifest.display == WebAppManifest.DisplayMode.MINIMAL_UI
    }

    fun list(context: Context): List<InstalledWebApp> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        InstalledWebApp(
                            id = o.optString("id"),
                            startUrl = o.optString("startUrl"),
                            scope = o.optString("scope"),
                            name = o.optString("name"),
                            shortName = o.optString("shortName"),
                            displayMode = o.optString("displayMode", "STANDALONE"),
                            themeColor = if (o.has("themeColor") && !o.isNull("themeColor")) o.optInt("themeColor") else null,
                            backgroundColor = if (o.has("backgroundColor") && !o.isNull("backgroundColor")) o.optInt("backgroundColor") else null,
                            iconPath = o.optString("iconPath").ifBlank { null },
                        )
                    )
                }
            }
        } catch (e: Exception) {
            UHLog.w("webapp", "Failed parsing installed webapps: ${e.message}")
            emptyList()
        }
    }

    fun findById(context: Context, id: String): InstalledWebApp? =
        list(context).firstOrNull { it.id == id }

    fun save(context: Context, app: InstalledWebApp) {
        val current = list(context).filterNot { it.id == app.id || it.startUrl == app.startUrl }.toMutableList()
        current.add(0, app)
        val arr = JSONArray()
        current.forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("startUrl", item.startUrl)
                put("scope", item.scope)
                put("name", item.name)
                put("shortName", item.shortName)
                put("displayMode", item.displayMode)
                if (item.themeColor != null) put("themeColor", item.themeColor)
                if (item.backgroundColor != null) put("backgroundColor", item.backgroundColor)
                if (item.iconPath != null) put("iconPath", item.iconPath)
            })
        }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, arr.toString())
            .apply()
    }

    fun saveIconToDisk(context: Context, id: String, bitmap: Bitmap): String? {
        return try {
            val dir = File(context.applicationContext.filesDir, "webapp_icons").apply { mkdirs() }
            val safeId = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val file = File(dir, "$safeId.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            UHLog.w("webapp", "Failed persisting web app icon: ${e.message}")
            null
        }
    }

    suspend fun resolveSiteIconBitmap(
        context: Context,
        pageUrl: String,
        tabIcon: Bitmap?,
        manifest: WebAppManifest?,
    ): Bitmap = withContext(Dispatchers.IO) {
        // 1. Prefer highest-resolution manifest icon (non-SVG raster bitmap)
        if (manifest != null && manifest.icons.isNotEmpty()) {
            val sortedIcons = manifest.icons
                .filter { icon ->
                    val src = icon.src.lowercase()
                    val mime = icon.type?.lowercase().orEmpty()
                    !src.endsWith(".svg") && !mime.contains("svg")
                }
                .sortedByDescending { icon ->
                    val maxDim = icon.sizes.maxOfOrNull { maxOf(it.width, it.height) } ?: 0
                    val maskableBoost = if (icon.purpose.any { it == WebAppManifest.Icon.Purpose.MASKABLE }) 20 else 0
                    maxDim + maskableBoost
                }

            for (icon in sortedIcons.take(4)) {
                val resolved = resolveUrl(manifest.startUrl.ifBlank { pageUrl }, icon.src) ?: continue
                val bmp = downloadBitmap(resolved)
                if (bmp != null && bmp.width >= 32 && bmp.height >= 32) {
                    return@withContext bmp
                }
            }
        }

        // 2. Use GeckoView's live tab favicon if crisp enough
        if (tabIcon != null && !tabIcon.isRecycled && tabIcon.width >= 32 && tabIcon.height >= 32) {
            return@withContext tabIcon
        }

        // 3. Fetch high-res favicon via domain favicon endpoint
        val fallbackIconUrl = ShortcutStore.iconUrlFor(pageUrl)
        if (fallbackIconUrl.isNotBlank()) {
            val fetched = downloadBitmap(fallbackIconUrl)
            if (fetched != null && fetched.width >= 24 && fetched.height >= 24) {
                return@withContext fetched
            }
        }

        // 4. Fallback to tabIcon of any size if present
        if (tabIcon != null && !tabIcon.isRecycled && tabIcon.width > 0 && tabIcon.height > 0) {
            return@withContext tabIcon
        }

        // 5. Generate clean branded monogram icon with site's theme color or UH palette
        val label = manifest?.shortName?.ifBlank { manifest.name }
            ?: ShortcutStore.shortNameFor(pageUrl).ifBlank { "W" }
        generateMonogramBitmap(
            label = label,
            accentColor = manifest?.themeColor ?: pickColorForHost(pageUrl),
        )
    }

    /**
     * Pads any site bitmap into a 192x192 Android Adaptive Icon bitmap so launchers never clip
     * square logos or letters inside circular/squircle masks.
     */
    fun toAdaptiveIconCompat(source: Bitmap, backgroundColor: Int? = null): IconCompat {
        val size = 192
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = backgroundColor ?: detectEdgeBackgroundColor(source)
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

        // Adaptive icon safe zone is central ~66% (approx 116dp inside 192px)
        val innerSize = (size * 0.56f)
        val left = (size - innerSize) / 2f
        val top = (size - innerSize) / 2f
        val dst = RectF(left, top, left + innerSize, top + innerSize)

        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, null, dst, bitmapPaint)
        return IconCompat.createWithAdaptiveBitmap(out)
    }

    fun pinWebAppOrShortcut(
        activityContext: Context,
        pageUrl: String,
        title: String,
        iconBitmap: Bitmap,
        manifest: WebAppManifest?,
    ): Boolean {
        if (pageUrl.isBlank() || pageUrl == "about:blank") return false
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(activityContext)) {
            Toast.makeText(activityContext, "Home screen pinning is not supported by this launcher", Toast.LENGTH_SHORT).show()
            return false
        }

        val isPwa = isPwaInstallable(manifest)
        val cleanLabel = title.trim()
            .ifBlank { manifest?.shortName?.ifBlank { manifest.name } ?: ShortcutStore.shortNameFor(pageUrl) }
            .ifBlank { "Web App" }
            .take(36)

        val manifestStartUrl = manifest?.startUrl
        val startUrl = if (isPwa && !manifestStartUrl.isNullOrBlank()) {
            resolveUrl(pageUrl, manifestStartUrl) ?: pageUrl
        } else {
            pageUrl
        }

        val shortcutId = if (isPwa) {
            "uh_pwa_${abs(startUrl.hashCode())}"
        } else {
            "uh_shortcut_${abs(pageUrl.hashCode())}"
        }

        val launchIntent = if (isPwa && manifest != null) {
            val savedIconPath = saveIconToDisk(activityContext, shortcutId, iconBitmap)
            val record = InstalledWebApp(
                id = shortcutId,
                startUrl = startUrl,
                scope = manifest.scope?.ifBlank { startUrl } ?: startUrl,
                name = cleanLabel,
                shortName = cleanLabel,
                displayMode = manifest.display.name,
                themeColor = manifest.themeColor,
                backgroundColor = manifest.backgroundColor,
                iconPath = savedIconPath,
            )
            save(activityContext, record)

            Intent(activityContext, WebAppActivity::class.java).apply {
                action = WebAppActivity.ACTION_LAUNCH_WEBAPP
                data = Uri.parse(startUrl)
                putExtra(WebAppActivity.EXTRA_WEBAPP_ID, shortcutId)
                putExtra(WebAppActivity.EXTRA_START_URL, startUrl)
                putExtra(WebAppActivity.EXTRA_TITLE, cleanLabel)
                putExtra(WebAppActivity.EXTRA_DISPLAY_MODE, manifest.display.name)
                if (manifest.themeColor != null) {
                    putExtra(WebAppActivity.EXTRA_THEME_COLOR, manifest.themeColor!!)
                }
                if (manifest.backgroundColor != null) {
                    putExtra(WebAppActivity.EXTRA_BG_COLOR, manifest.backgroundColor!!)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
        } else {
            Intent(activityContext, HomeActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(pageUrl)
            }
        }

        val adaptiveIcon = toAdaptiveIconCompat(iconBitmap, manifest?.backgroundColor)
        val shortcutInfo = ShortcutInfoCompat.Builder(activityContext, shortcutId)
            .setShortLabel(cleanLabel)
            .setLongLabel(cleanLabel)
            .setIcon(adaptiveIcon)
            .setIntent(launchIntent)
            .build()

        val requested = ShortcutManagerCompat.requestPinShortcut(activityContext, shortcutInfo, null)
        if (requested) {
            Toast.makeText(
                activityContext,
                if (isPwa) "Installing $cleanLabel…" else "Adding $cleanLabel to Home screen…",
                Toast.LENGTH_SHORT
            ).show()
        }
        return requested
    }

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                .build()
            UhHttpClient.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                if (bytes.isEmpty() || bytes.size > 4 * 1024 * 1024) return null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveUrl(baseUrl: String, relativeOrAbsolute: String): String? {
        val raw = relativeOrAbsolute.trim()
        if (raw.isBlank()) return null
        return try {
            if (raw.startsWith("http://") || raw.startsWith("https://")) {
                raw
            } else {
                URI(baseUrl).resolve(raw).toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun detectEdgeBackgroundColor(bitmap: Bitmap): Int {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 2 || h <= 2) return Color.WHITE
            val corners = listOf(
                bitmap.getPixel(0, 0),
                bitmap.getPixel(w - 1, 0),
                bitmap.getPixel(0, h - 1),
                bitmap.getPixel(w - 1, h - 1),
            )
            val opaque = corners.filter { Color.alpha(it) > 220 }
            if (opaque.size >= 3) {
                Color.rgb(Color.red(opaque[0]), Color.green(opaque[0]), Color.blue(opaque[0]))
            } else {
                Color.WHITE
            }
        } catch (_: Exception) {
            Color.WHITE
        }
    }

    private fun pickColorForHost(url: String): Int {
        val palette = intArrayOf(
            0xFF457B9D.toInt(),
            0xFF2A9D8F.toInt(),
            0xFF4E6CEF.toInt(),
            0xFF7B5EA7.toInt(),
            0xFFD96B43.toInt(),
            0xFF3A7CA5.toInt(),
        )
        return palette[abs(url.hashCode()) % palette.size]
    }

    private fun generateMonogramBitmap(label: String, accentColor: Int): Bitmap {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 42f, 42f, bg)

        val letter = label.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "W"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 96f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val baseline = (size / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(letter, size / 2f, baseline, textPaint)
        return bmp
    }
}
