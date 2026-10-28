package app.harvester.companion

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog

class TabGridDialog(
    ctx: Context,
    private val manager: TabManager,
    private val onSwitch: (Long) -> Unit,
    private val onNew: () -> Unit
) : Dialog(ctx) {

    private var adapter: Adapter? = null
    private var mode: Int = MODE_NORMAL
    private var filterGroupId: Long = 0L

    companion object {
        private const val MODE_NORMAL = 0
        private const val MODE_INCOGNITO = 1
        private const val MODE_GROUPS = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_tab_grid)
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        window?.setBackgroundDrawableResource(R.color.bg)
        @Suppress("DEPRECATION")
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

val grid = findViewById<RecyclerView>(R.id.tabGrid)
val isLandscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
val screenWidthDp = context.resources.configuration.screenWidthDp
val columns = if (isLandscape) {
    when {
        screenWidthDp >= 1280 -> 5
        screenWidthDp >= 917 -> 4
        screenWidthDp >= 600 -> 3
        else -> 2
    }
} else {
    when {
        screenWidthDp >= 800 -> 4
        screenWidthDp >= 480 -> 3
        else -> 2
    }
}
grid.layoutManager = GridLayoutManager(context, columns)
        adapter = Adapter(context, emptyList(), 0L,
            onTap = { id -> dismiss(); onSwitch(id) },
            onClose = { id -> closeOne(id) },
            onGroupLongPress = { groupId -> showGroupMenu(groupId) }
        )
        grid.adapter = adapter

        findViewById<View>(R.id.tabsBack).setOnClickListener { dismiss() }
        findViewById<View>(R.id.tabsIncognito).setOnClickListener { toggleIncognito() }
        findViewById<View>(R.id.tabsGroups).setOnClickListener { toggleGroups() }
        findViewById<View>(R.id.tabsMenu).setOnClickListener { showTabMenu() }

        findViewById<View>(R.id.tabBottomNew).setOnClickListener {
            dismiss()
            if (mode == MODE_INCOGNITO) manager.openNew("about:blank", incognito = true)
            else manager.openNew("about:blank")
            onNew()
        }
        findViewById<View>(R.id.tabBottomCloseAll).setOnClickListener { confirmCloseAll() }
        findViewById<View>(R.id.tabBottomGrid).setOnClickListener { refresh() }
        findViewById<View>(R.id.tabBottomSelect).setOnClickListener { toast("Select mode coming soon") }
        findViewById<View>(R.id.tabBottomCount).setOnClickListener { toast("${currentTabs().size} tabs") }

        refresh()
    }

    private fun currentTabs(): List<TabEntity> {
        return when (mode) {
            MODE_INCOGNITO -> manager.listIncognito()
            MODE_GROUPS -> if (filterGroupId != 0L) manager.listByGroup(filterGroupId)
                            else manager.listUngrouped()
            else -> manager.listNormal()
        }
    }

private fun refresh() {
    val tabs = currentTabs()
    adapter?.update(tabs, manager.currentId())
    findViewById<TextView>(R.id.tabGridCount).text = tabs.size.toString()
    if (mode == MODE_INCOGNITO) {
        findViewById<ImageButton>(R.id.tabsIncognito).setColorFilter(0xFF22D3EE.toInt())
    } else {
        findViewById<ImageButton>(R.id.tabsIncognito).clearColorFilter()
    }
    val strip = findViewById<LinearLayout>(R.id.tabStrip)
    if (mode == MODE_GROUPS && filterGroupId != 0L) {
        strip.visibility = View.VISIBLE
        strip.removeAllViews()
        for (t in tabs) {
            val chip = android.widget.TextView(context).apply {
                text = t.title.ifBlank { "New Tab" }.take(20)
                setTextColor(
                    if (t.id == manager.currentId()) 0xFF22D3EE.toInt() else 0xFF8A96A8.toInt()
                )
                textSize = 12f
                setPadding(24, 12, 24, 12)
                setOnClickListener { onSwitch(t.id) }
            }
            strip.addView(chip)
        }
    } else {
        strip.visibility = View.GONE
    }
}

    private fun toggleIncognito() {
        if (mode == MODE_INCOGNITO) {
            mode = MODE_NORMAL
        } else {
            mode = MODE_INCOGNITO
        }
        refresh()
    }

    private fun toggleGroups() {
        mode = MODE_GROUPS
        filterGroupId = 0L
        refresh()
    }

private fun closeOne(id: Long) {
    val entity = manager.list().firstOrNull { it.id == id }
    if (entity != null && entity.groupId != 0L) {
        val remainingInGroup = manager.listByGroup(entity.groupId).size
        if (remainingInGroup <= 1) {
            val group = TabGroupStore.byId(context, entity.groupId)
            AlertDialog.Builder(context)
                .setTitle("Close last tab in group?")
                .setMessage("Delete group \"${group?.name ?: "unnamed"}\" as well?")
                .setPositiveButton("Delete group") { _, _ ->
                    TabGroupStore.delete(context, entity.groupId)
                    manager.close(id)
                    refresh()
                }
                .setNegativeButton("Keep group") { _, _ ->
                    manager.close(id)
                    refresh()
                }
                .show()
            return
        }
    }
    manager.close(id)
    val remaining = currentTabs()
    if (remaining.isEmpty()) {
        if (mode == MODE_INCOGNITO) {
            manager.closeAllIncognito()
            mode = MODE_NORMAL
            refresh()
            if (currentTabs().isEmpty()) { dismiss(); onNew() }
        } else {
            dismiss()
            onNew()
        }
    } else {
        refresh()
    }
}

    private fun confirmCloseAll() {
        val count = currentTabs().size
        if (count == 0) { dismiss(); return }
        val label = when (mode) {
            MODE_INCOGNITO -> "Close all $count incognito tabs?"
            else -> "Close all $count tabs?"
        }
        AlertDialog.Builder(context)
            .setTitle(label)
            .setMessage("This cannot be undone.")
            .setPositiveButton("Close all") { _, _ ->
                if (mode == MODE_INCOGNITO) {
                    manager.closeAllIncognito()
                    mode = MODE_NORMAL
                } else if (mode == MODE_NORMAL) {
                    manager.closeAll()
                }
                dismiss()
                onNew()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTabMenu() {
        val sheet = BottomSheetDialog(context)
        val v = LayoutInflater.from(context).inflate(R.layout.dialog_tab_menu, null)
        sheet.setContentView(v)
        sheet.behavior.isDraggable = false

        v.findViewById<View>(R.id.tmSelectTabs)?.setOnClickListener {
            sheet.dismiss(); toast("Select mode coming soon")
        }
        v.findViewById<View>(R.id.tmNewTab)?.setOnClickListener {
            sheet.dismiss()
            manager.openNew("about:blank")
            dismiss()
            onNew()
        }
        v.findViewById<View>(R.id.tmNewIncognito)?.setOnClickListener {
            sheet.dismiss()
            manager.openNew("about:blank", incognito = true)
            dismiss()
            onNew()
        }
        v.findViewById<View>(R.id.tmNewGroup)?.setOnClickListener {
            sheet.dismiss()
            promptCreateGroup()
        }
        v.findViewById<View>(R.id.tmCloseAll)?.setOnClickListener {
            sheet.dismiss()
            confirmCloseAll()
        }
        v.findViewById<View>(R.id.tmDeleteData)?.setOnClickListener {
            sheet.dismiss()
            confirmDeleteData()
        }
        sheet.show()
    }

    private fun promptCreateGroup() {
        val input = EditText(context).apply { hint = "Group name"; setSingleLine() }
        AlertDialog.Builder(context)
            .setTitle("New tab group")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Group" }
                TabGroupStore.create(context, name)
                mode = MODE_GROUPS
                filterGroupId = 0L
                refresh()
                toast("Group '$name' created")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGroupMenu(groupId: Long) {
        val group = TabGroupStore.byId(context, groupId) ?: return
        AlertDialog.Builder(context)
            .setTitle(group.name)
            .setItems(arrayOf("Open", "Rename", "Delete group")) { _, which ->
                when (which) {
                    0 -> {
                        filterGroupId = groupId
                        mode = MODE_GROUPS
                        refresh()
                    }
                    1 -> {
                        val input = EditText(context).apply { setText(group.name); setSingleLine() }
                        AlertDialog.Builder(context)
                            .setTitle("Rename group")
                            .setView(input)
                            .setPositiveButton("Save") { _, _ ->
                                TabGroupStore.rename(context, groupId, input.text.toString().trim())
                                refresh()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    2 -> {
                        TabGroupStore.delete(context, groupId)
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun confirmDeleteData() {
        AlertDialog.Builder(context)
            .setTitle("Delete browsing data?")
            .setMessage("Cookies, cache, and site storage will be cleared.")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                    android.webkit.CookieManager.getInstance().flush()
                    android.webkit.WebStorage.getInstance().deleteAllData()
                    toast("Data cleared")
                } catch (_: Exception) { toast("Failed") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

private fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()

private val configCallbacks = object : android.content.ComponentCallbacks {
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        val grid = findViewById<RecyclerView>(R.id.tabGrid)
        val isLandscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val screenWidthDp = newConfig.screenWidthDp
        val columns = if (isLandscape) {
            when {
                screenWidthDp >= 1280 -> 5
                screenWidthDp >= 917 -> 4
                screenWidthDp >= 600 -> 3
                else -> 2
            }
        } else {
            when {
                screenWidthDp >= 800 -> 4
                screenWidthDp >= 480 -> 3
                else -> 2
            }
        }
        grid.layoutManager = GridLayoutManager(context, columns)
    }
    override fun onLowMemory() {}
}

override fun show() {
    super.show()
    context.registerComponentCallbacks(configCallbacks)
}

override fun dismiss() {
    context.unregisterComponentCallbacks(configCallbacks)
    super.dismiss()
}
    private class Adapter(
        private val ctx: Context,
        private var items: List<TabEntity>,
        private var currentId: Long,
        private val onTap: (Long) -> Unit,
        private val onClose: (Long) -> Unit,
        private val onGroupLongPress: (Long) -> Unit
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
            val card = h.itemView as? com.google.android.material.card.MaterialCardView
            val ctx = h.itemView.context
            if (t.id == currentId) {
                card?.strokeColor = androidx.core.content.ContextCompat.getColor(ctx, R.color.accent)
                card?.strokeWidth = (2 * ctx.resources.displayMetrics.density).toInt()
                h.itemView.alpha = 1f
            } else {
                card?.strokeColor = androidx.core.content.ContextCompat.getColor(ctx, R.color.border)
                card?.strokeWidth = (1 * ctx.resources.displayMetrics.density).toInt()
                h.itemView.alpha = 0.85f
            }
            h.itemView.setOnClickListener { onTap(t.id) }
            h.close.setOnClickListener { onClose(t.id) }
            h.itemView.setOnLongClickListener {
                if (t.groupId != 0L) { onGroupLongPress(t.groupId); true } else false
            }
        }

        override fun getItemCount(): Int = items.size

        fun update(newItems: List<TabEntity>, current: Long) {
            items = newItems
            currentId = current
            notifyDataSetChanged()
        }
    }
}
