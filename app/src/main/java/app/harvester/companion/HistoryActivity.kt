package app.harvester.companion

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var adapter: Adapter
    private lateinit var empty: TextView
    private lateinit var list: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        list = findViewById(R.id.historyList)
        empty = findViewById(R.id.historyEmpty)
        adapter = Adapter(
            onOpen = { url -> open(url) },
            onDelete = { id -> HistoryStore.get(this).delete(id); reload() }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<ImageButton>(R.id.historyBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.historyClear).setOnClickListener { confirmClear() }
        reload()
    }

    private fun reload() {
        val items = HistoryStore.get(this).allHistory()
        adapter.submit(items)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun open(url: String) {
        // open in MainActivity — if no tabs, it opens one
        val intent = android.content.Intent(this, MainActivity::class.java).apply {
            data = android.net.Uri.parse(url)
            addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear browsing history?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                HistoryStore.get(this).clearHistory()
                reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    class Adapter(
        private val onOpen: (String) -> Unit,
        private val onDelete: (Long) -> Unit
    ) : RecyclerView.Adapter<Adapter.VH>() {
        private val items = mutableListOf<HistoryStore.Entry>()
        private val dayFmt = SimpleDateFormat("EEEE, MMM d", Locale.US)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            h.title.text = e.title.ifBlank { hostOf(e.url) }
            h.url.text = e.url
            h.time.text = relativeTime(e.visitedAt)
            h.itemView.setOnClickListener { onOpen(e.url) }
            h.delete.setOnClickListener { onDelete(e.id) }
        }

        override fun getItemCount() = items.size

        fun submit(newList: List<HistoryStore.Entry>) {
            items.clear(); items.addAll(newList); notifyDataSetChanged()
        }

        private fun hostOf(url: String): String = try {
            java.net.URI(url).host ?: url
        } catch (_: Exception) { url }

        private fun relativeTime(t: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - t
            return when {
                diff < 60_000 -> "just now"
                diff < 3_600_000 -> "${diff / 60_000}m"
                diff < 86_400_000 -> "${diff / 3_600_000}h"
                diff < 7L * 86_400_000 -> "${diff / 86_400_000}d"
                else -> dayFmt.format(Date(t))
            }
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.histTitle)
            val url: TextView = v.findViewById(R.id.histUrl)
            val time: TextView = v.findViewById(R.id.histTime)
            val delete: ImageButton = v.findViewById(R.id.histDelete)
        }
    }
}
