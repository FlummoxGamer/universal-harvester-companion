package app.harvester.companion

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import mozilla.components.concept.storage.VisitInfo

class SuggestionAdapter(
    private val onClick: (String) -> Unit,
    private val onDeleteQuery: (Long) -> Unit,
    private val onDeletePage: (Long) -> Unit,
    private val onCopy: (String) -> Unit,
    private val onEdit: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Query(val text: String) : Row()
        data class Page(val url: String, val title: String) : Row()
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
                h.delete.visibility = View.GONE
                h.arrow.visibility = View.VISIBLE
            }
            is Row.Page -> {
                val h = holder as PageVH
                h.title.text = r.title.ifBlank { hostOf(r.url) }
                h.url.text = r.url
                h.itemView.setOnClickListener { onClick(r.url) }
                h.edit.setOnClickListener { onEdit(r.url) }
                h.copy.setOnClickListener { onCopy(r.url) }
                h.delete.setOnClickListener { onDeletePage(0L) }
            }
        }
    }

    override fun getItemCount() = rows.size

    fun submitAll(queries: List<String>, pages: List<VisitInfo>) {
        rows.clear()
        queries.forEach { rows.add(Row.Query(it)) }
        pages.forEach { rows.add(Row.Page(it.url, it.title ?: "")) }
        notifyDataSetChanged()
    }

    private fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: url
    } catch (_: Exception) { url }

    class QueryVH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.sugIcon)
        val text: TextView = v.findViewById(R.id.sugText)
        val delete: ImageButton = v.findViewById(R.id.sugDelete)
        val arrow: ImageView = v.findViewById(R.id.sugArrow)
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
