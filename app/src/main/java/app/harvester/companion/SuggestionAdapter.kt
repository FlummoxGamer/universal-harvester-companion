package app.harvester.companion

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SuggestionAdapter(
    private val onClick: (String) -> Unit,
    private val onEdit: (String) -> Unit,
    private val onCopy: (String) -> Unit,
    private val onDelete: (Long) -> Unit
) : RecyclerView.Adapter<SuggestionAdapter.VH>() {

    private val items = mutableListOf<HistoryStore.Entry>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.sugIcon)
        val title: TextView = v.findViewById(R.id.sugTitle)
        val url: TextView = v.findViewById(R.id.sugUrl)
        val edit: ImageButton = v.findViewById(R.id.sugEdit)
        val copy: ImageButton = v.findViewById(R.id.sugCopy)
        val delete: ImageButton = v.findViewById(R.id.sugDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_suggestion, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.title.text = e.title.ifBlank { hostOf(e.url) }
        h.url.text = e.url
        h.itemView.setOnClickListener { onClick(e.url) }
        h.edit.setOnClickListener { onEdit(e.url) }
        h.copy.setOnClickListener { onCopy(e.url) }
        h.delete.setOnClickListener { onDelete(e.id) }
    }

    override fun getItemCount() = items.size

    fun submit(newList: List<HistoryStore.Entry>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    private fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: url
    } catch (_: Exception) { url }
}
