package app.harvester.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.security.MessageDigest

class ShortcutAdapter(
    private val onOpen: (Shortcut) -> Unit,
    private val onAdd: () -> Unit,
    private val onLongPress: (Shortcut) -> Unit
) : RecyclerView.Adapter<ShortcutAdapter.VH>() {

    private val items = mutableListOf<Shortcut>()
    private val iconCache = mutableMapOf<String, Bitmap?>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.shortcutIcon)
        val plus: TextView = v.findViewById(R.id.shortcutPlus)
        val label: TextView = v.findViewById(R.id.shortcutLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_shortcut, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        if (position == items.size) {
            h.icon.setImageDrawable(null)
            h.icon.tag = null
            h.icon.visibility = View.GONE
            h.plus.visibility = View.VISIBLE
            h.label.text = "Add"
            h.itemView.setOnClickListener { onAdd() }
            h.itemView.setOnLongClickListener { false }
            return
        }
        val s = items[position]
        h.icon.visibility = View.VISIBLE
        h.plus.visibility = View.GONE
        h.label.text = s.name
        h.icon.setImageDrawable(null)
        h.icon.tag = s.iconUrl
        
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        params.gravity = Gravity.CENTER
        h.icon.layoutParams = params
        h.icon.scaleType = ImageView.ScaleType.FIT_CENTER
        
        loadIcon(h, s.iconUrl)
        h.itemView.setOnClickListener { onOpen(s) }
        h.itemView.setOnLongClickListener { onLongPress(s); true }
    }

    override fun getItemCount(): Int = items.size + 1

    fun submit(newList: List<Shortcut>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    fun getItemAt(position: Int): Shortcut = items[position]
    
    fun getItems(): List<Shortcut> = items

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                java.util.Collections.swap(items, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                java.util.Collections.swap(items, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
    }

    private fun loadIcon(h: VH, iconUrl: String) {
        if (iconUrl.isBlank()) {
            setFallback(h)
            return
        }

        val cached = iconCache[iconUrl]
        if (cached != null) {
            if (h.icon.tag == iconUrl) applyImage(h, cached)
            return
        }

        val context = h.itemView.context
        val file = getCacheFile(context, iconUrl)

        if (file.exists()) {
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
            if (bmp != null) {
                iconCache[iconUrl] = bmp
                if (h.icon.tag == iconUrl) applyImage(h, bmp)
                
                val age = System.currentTimeMillis() - file.lastModified()
                if (age > 7L * 24 * 60 * 60 * 1000) {
                    fetchAndCache(context, iconUrl, file, h)
                }
                return
            }
        }

        fetchAndCache(context, iconUrl, file, h)
    }

    private fun fetchAndCache(context: Context, iconUrl: String, file: File, h: VH) {
        Thread {
            var bmp: Bitmap? = null
            val urlsToTry = mutableListOf(iconUrl)
            try {
                val host = java.net.URI(iconUrl).host
                if (host != null && !iconUrl.contains("favicon.ico")) {
                    urlsToTry.add("https://$host/favicon.ico")
                }
            } catch (_: Exception) {}

            for (url in urlsToTry) {
                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .header("Cache-Control", "no-cache")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            response.body?.byteStream()?.use { stream ->
                                bmp = BitmapFactory.decodeStream(stream)
                            }
                            if (bmp != null) break
                        }
                    }
                } catch (_: Exception) { }
            }

            h.itemView.post {
                iconCache[iconUrl] = bmp
                if (bmp != null) {
                    try {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { out ->
                            bmp!!.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    } catch (_: Exception) {}
                    if (h.icon.tag == iconUrl) applyImage(h, bmp!!)
                } else {
                    if (h.icon.tag == iconUrl) setFallback(h)
                }
            }
        }.start()
    }

private fun applyImage(h: VH, bmp: Bitmap) {
    val transparentBmp = makeCornersTransparent(bmp)
    h.icon.setImageBitmap(transparentBmp)
    val padding = (6 * h.itemView.context.resources.displayMetrics.density).toInt()
    h.icon.setPadding(padding, padding, padding, padding)
}

private fun makeCornersTransparent(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    if (w < 2 || h < 2) return bitmap

    val c1 = bitmap.getPixel(0, 0)
    val c2 = bitmap.getPixel(w - 1, 0)
    val c3 = bitmap.getPixel(0, h - 1)
    val c4 = bitmap.getPixel(w - 1, h - 1)

    val tolerance = 30
    if (isColorSimilar(c1, c2, tolerance) && 
        isColorSimilar(c1, c3, tolerance) && 
        isColorSimilar(c1, c4, tolerance)) {
        
        if (android.graphics.Color.alpha(c1) == 0) return bitmap

        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        for (x in 0 until w) {
            for (y in 0 until h) {
                val pixel = result.getPixel(x, y)
                if (isColorSimilar(pixel, c1, tolerance)) {
                    result.setPixel(x, y, android.graphics.Color.TRANSPARENT)
                }
            }
        }
        return result
    }
    return bitmap
}

private fun isColorSimilar(c1: Int, c2: Int, tolerance: Int): Boolean {
    return Math.abs(android.graphics.Color.red(c1) - android.graphics.Color.red(c2)) <= tolerance &&
           Math.abs(android.graphics.Color.green(c1) - android.graphics.Color.green(c2)) <= tolerance &&
           Math.abs(android.graphics.Color.blue(c1) - android.graphics.Color.blue(c2)) <= tolerance
}

    private fun setFallback(h: VH) {
        h.icon.setImageResource(R.drawable.ic_globe)
        val padding = (16 * h.itemView.context.resources.displayMetrics.density).toInt()
        h.icon.setPadding(padding, padding, padding, padding)
    }

    private fun getCacheFile(context: Context, url: String): File {
        val hash = try {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(url.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            url.hashCode().toString()
        }
        val dir = File(context.filesDir, "icon_cache")
        return File(dir, "$hash.png")
    }
}
