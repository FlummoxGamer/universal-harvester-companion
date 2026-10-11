package app.harvester.companion

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

// B3: dismiss on tab tap so MainActivity's dismiss listener re-attaches;
// auto-open a fresh tab if the last one is closed from the grid.
class TabGridDialog(
    ctx: Context,
    private val manager: TabManager,
    private val onSwitch: (Long) -> Unit,
    private val onNew: () -> Unit
) : Dialog(ctx) {

    private var adapter: Adapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_tab_grid)
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        val grid = findViewById<RecyclerView>(R.id.tabGrid)
        grid.layoutManager = GridLayoutManager(context, 2)
        adapter = Adapter(
            manager.list(),
            manager.currentId(),
            onTap = { id ->
                dismiss()
                onSwitch(id)
            },
            onClose = { id ->
                manager.close(id)
                val remaining = manager.list()
                adapter?.update(remaining, manager.currentId())
                if (remaining.isEmpty()) {
                    dismiss()
                    onNew()
                }
            }
        )
        grid.adapter = adapter

        findViewById<Button>(R.id.btnNewTab).setOnClickListener {
            dismiss()
            onNew()
        }
        findViewById<Button>(R.id.btnCloseAll).setOnClickListener {
            manager.closeAll()
            dismiss()
            onNew()
        }
    }

    private class Adapter(
        private var items: List<TabEntity>,
        private var currentId: Long,
        private val onTap: (Long) -> Unit,
        private val onClose: (Long) -> Unit
    ) : RecyclerView.Adapter<Adapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.tabTitle)
            val url: TextView = v.findViewById(R.id.tabUrl)
            val close: ImageView = v.findViewById(R.id.tabClose)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_tab_grid, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val t = items[position]
            h.title.text = t.title.ifBlank { "New Tab" }
            h.url.text = t.url.take(160)
            h.itemView.alpha = if (t.id == currentId) 1f else 0.72f
            h.itemView.setOnClickListener { onTap(t.id) }
            h.close.setOnClickListener { onClose(t.id) }
        }

        override fun getItemCount(): Int = items.size

        fun update(newItems: List<TabEntity>, current: Long) {
            items = newItems
            currentId = current
            notifyDataSetChanged()
        }
    }
}
