package app.harvester.companion

import android.app.DownloadManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadsActivity : AppCompatActivity() {

    private lateinit var adapter: Adapter
    private lateinit var emptyView: TextView
    private lateinit var list: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        list = findViewById(R.id.downloadsList)
        emptyView = findViewById(R.id.downloadsEmpty)
        adapter = Adapter { item -> openItem(item) }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<ImageButton>(R.id.downloadsBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.downloadsMenu).setOnClickListener { showSortMenu() }

        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val items = DownloadsQuery.list(this)
        adapter.submit(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openItem(item: DownloadsQuery.Item) {
        try {
            val uri = DownloadsQuery.uriFor(item.id)
            val mime = contentResolver.getType(uri) ?: item.mime
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            // fallback to system downloads
            try {
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            } catch (_: Exception) {}
        }
    }

    private fun showSortMenu() {
        val opts = arrayOf("Sort by date", "Sort by size", "Sort by name")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setItems(opts) { _, which ->
                DownloadsQuery.setSort(which)
                reload()
            }
            .show()
    }

    class Adapter(private val onClick: (DownloadsQuery.Item) -> Unit)
        : RecyclerView.Adapter<Adapter.VH>() {

        private val items = mutableListOf<DownloadsQuery.Item>()

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.dlName)
            val meta: TextView = v.findViewById(R.id.dlMeta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_download_manager, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val item = items[position]
            h.name.text = item.title.ifBlank { item.filename }
            h.meta.text = "${item.sizeText} • ${item.dateText}"
            h.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size

        fun submit(newList: List<DownloadsQuery.Item>) {
            items.clear()
            items.addAll(newList)
            notifyDataSetChanged()
        }
    }
}
