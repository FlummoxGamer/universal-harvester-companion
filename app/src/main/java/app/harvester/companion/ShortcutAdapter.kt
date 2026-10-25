package app.harvester.companion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

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
        loadIcon(h.icon, s.iconUrl)
        h.itemView.setOnClickListener { onOpen(s) }
        h.itemView.setOnLongClickListener { onLongPress(s); true }
    }

    override fun getItemCount(): Int = items.size + 1

    fun submit(newList: List<Shortcut>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

private fun loadIcon(view: ImageView, iconUrl: String) {
    if (iconUrl.isBlank()) {
        view.setImageResource(R.drawable.ic_globe)
        return
    }
    val cached = iconCache[iconUrl]
    if (cached != null) {
        if (view.tag == iconUrl) view.setImageBitmap(cached)
        return
    }
    if (iconCache.containsKey(iconUrl)) return

    Thread {
        var bmp: Bitmap? = null
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .cache(null)
                .build()
            val request = okhttp3.Request.Builder()
                .url(iconUrl)
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.byteStream()?.use { stream ->
                        bmp = BitmapFactory.decodeStream(stream)
                    }
                }
            }
        } catch (_: Exception) { }
        
        view.post {
            iconCache[iconUrl] = bmp
            if (bmp != null && view.tag == iconUrl) {
                view.setImageBitmap(bmp)
            } else if (bmp == null && view.tag == iconUrl) {
                view.setImageResource(R.drawable.ic_globe)
            }
        }
    }.start()
}
}
