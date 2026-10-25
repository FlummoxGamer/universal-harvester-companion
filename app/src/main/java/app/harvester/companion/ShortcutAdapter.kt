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
            h.icon.setPadding(0, 0, 0, 0)
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
        h.icon.layoutParams = params
        h.icon.scaleType = ImageView.ScaleType.CENTER_CROP
        
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
        val density = h.itemView.context.resources.displayMetrics.density
        val sizePx = (32 * density).toInt()
        
        h.icon.setImageBitmap(bmp)
        if (bmp.width >= sizePx && bmp.height >= sizePx) {
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            h.icon.layoutParams = params
            h.icon.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            val params = FrameLayout.LayoutParams(sizePx, sizePx)
            params.gravity = Gravity.CENTER
            h.icon.layoutParams = params
            h.icon.scaleType = ImageView.ScaleType.FIT_CENTER
        }
    }

    private fun setFallback(h: VH) {
        h.icon.setImageResource(R.drawable.ic_globe)
        val density = h.itemView.context.resources.displayMetrics.density
        val sizePx = (32 * density).toInt()
        val params = FrameLayout.LayoutParams(sizePx, sizePx)
        params.gravity = Gravity.CENTER
        h.icon.layoutParams = params
        h.icon.scaleType = ImageView.ScaleType.FIT_CENTER
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
