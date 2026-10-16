package app.harvester.companion

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class SuggestionAdapter(
    private val onClick: (String) -> Unit,
    private val onDeleteQuery: (Long) -> Unit,
    private val onDeletePage: (Long) -> Unit,
    private val onCopy: (String) -> Unit,
    private val onEdit: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Query(val id: Long, val text: String) : Row()
        data class Page(val id: Long, val url: String, val title: String) : Row()
    }

    private val rows = mutableListOf<Row>()

    companion object {
        private const val TYPE_QUERY = 0
        private const val TYPE_PAGE = 1
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Query -> TYPE_QUERY
        is Row.Page -> TYPE_PAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_QUERY) {
            QueryVH(inflater.inflate(R.layout.item_suggestion_query, parent, false))
        } else {
            PageVH(inflater.inflate(R.layout.item_suggestion, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val r = rows[position]) {
            is Row.Query -> {
                val h = holder as QueryVH
                h.text.text = r.text
                h.itemView.setOnClickListener { onClick(r.text) }
                h.delete.visibility = View.VISIBLE
                h.delete.setOnClickListener { onDeleteQuery(r.id) }
            }
            is Row.Page -> {
                val h = holder as PageVH
                h.title.text = r.title.ifBlank { hostOf(r.url) }
                h.url.text = r.url
                h.itemView.setOnClickListener { onClick(r.url) }
                h.edit.setOnClickListener { onEdit(r.url) }
                h.copy.setOnClickListener { onCopy(r.url) }
                h.delete.setOnClickListener { onDeletePage(r.id) }
            }
        }
    }

    override fun getItemCount() = rows.size

    fun submitQueries(queries: List<HistoryStore.SearchQuery>) {
        rows.removeAll { it is Row.Query }
        val insertAt = 0
        queries.forEachIndexed { i, q ->
            rows.add(insertAt + i, Row.Query(q.id, q.query))
        }
        notifyDataSetChanged()
    }

    fun submitPages(pages: List<HistoryStore.Entry>) {
        rows.removeAll { it is Row.Page }
        for (p in pages) rows.add(Row.Page(p.id, p.url, p.title))
        notifyDataSetChanged()
    }

    fun submitAll(queries: List<HistoryStore.SearchQuery>, pages: List<HistoryStore.Entry>) {
        rows.clear()
        queries.forEach { rows.add(Row.Query(it.id, it.query)) }
        pages.forEach { rows.add(Row.Page(it.id, it.url, it.title)) }
        notifyDataSetChanged()
    }

    fun hasQueries(): Boolean = rows.any { it is Row.Query }
    fun queryCount(): Int = rows.count { it is Row.Query }

    private fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: url
    } catch (_: Exception) { url }

    class QueryVH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.sugIcon)
        val text: TextView = v.findViewById(R.id.sugText)
        val delete: ImageButton = v.findViewById(R.id.sugDelete)
    }

    class PageVH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.sugIcon)
        val title: TextView = v.findViewById(R.id.sugTitle)
        val url: TextView = v.findViewById(R.id.sugUrl)
        val edit: ImageButton = v.findViewById(R.id.sugEdit)
        val copy: ImageButton = v.findViewById(R.id.sugCopy)
        val delete: ImageButton = v.findViewById(R.id.sugDelete)
    }
}
