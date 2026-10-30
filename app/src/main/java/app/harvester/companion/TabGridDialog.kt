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
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog

class TabGridDialog : DialogFragment() {

    var onSwitch: ((Long) -> Unit)? = null
    var onNew: (() -> Unit)? = null
    var onDismissCallback: (() -> Unit)? = null

    private lateinit var manager: TabManager
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
        setStyle(STYLE_NO_TITLE, R.style.Theme_UHCompanion)
        manager = TabManager.get(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.dialog_tab_grid, container, false)
        
        val grid = v.findViewById<RecyclerView>(R.id.tabGrid)
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val screenWidthDp = resources.configuration.screenWidthDp
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
        adapter = Adapter(requireContext(), emptyList(), 0L,
            onTap = { id -> dismiss(); onSwitch?.invoke(id) },
            onClose = { id -> closeOne(id) },
            onGroupLongPress = { groupId -> showGroupMenu(groupId) }
        )
        grid.adapter = adapter

        v.findViewById<View>(R.id.tabsBack).setOnClickListener { dismiss() }
        v.findViewById<View>(R.id.tabsIncognito).setOnClickListener { toggleIncognito(v) }
        v.findViewById<View>(R.id.tabsGroups).setOnClickListener { toggleGroups(v) }
        v.findViewById<View>(R.id.tabsMenu).setOnClickListener { showTabMenu(v) }

        v.findViewById<View>(R.id.tabBottomNew).setOnClickListener {
            dismiss()
            if (mode == MODE_INCOGNITO) manager.openNew("about:blank", incognito = true)
            else manager.openNew("about:blank")
            onNew?.invoke()
        }
        v.findViewById<View>(R.id.tabBottomCloseAll).setOnClickListener { confirmCloseAll() }
        v.findViewById<View>(R.id.tabBottomGrid).setOnClickListener { refresh(v) }
        v.findViewById<View>(R.id.tabBottomSelect).setOnClickListener { toast("Select mode coming soon") }
        v.findViewById<View>(R.id.tabBottomCount).setOnClickListener { toast("${currentTabs().size} tabs") }

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refresh(view)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog?.window?.setBackgroundDrawableResource(R.color.bg)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(dialog!!.window!!, true)
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onDismissCallback?.invoke()
    }

    private fun currentTabs(): List<TabEntity> {
        return when (mode) {
            MODE_INCOGNITO -> manager.listIncognito()
            MODE_GROUPS -> if (filterGroupId != 0L) manager.listByGroup(filterGroupId)
                            else manager.listUngrouped()
            else -> manager.listNormal()
        }
    }

    private fun refresh(v: View) {
        val tabs = currentTabs()
        adapter?.update(tabs, manager.currentId())
        v.findViewById<TextView>(R.id.tabGridCount).text = tabs.size.toString()
        if (mode == MODE_INCOGNITO) {
            v.findViewById<ImageButton>(R.id.tabsIncognito).setColorFilter(0xFF22D3EE.toInt())
        } else {
            v.findViewById<ImageButton>(R.id.tabsIncognito).clearColorFilter()
        }
        val strip = v.findViewById<LinearLayout>(R.id.tabStrip)
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
                    setOnClickListener { onSwitch?.invoke(t.id) }
                }
                strip.addView(chip)
            }
        } else {
            strip.visibility = View.GONE
        }
    }

    private fun toggleIncognito(v: View) {
        if (mode == MODE_INCOGNITO) mode = MODE_NORMAL else mode = MODE_INCOGNITO
        refresh(v)
    }

    private fun toggleGroups(v: View) {
        mode = MODE_GROUPS
        filterGroupId = 0L
        refresh(v)
    }

    private fun closeOne(id: Long) {
        val entity = manager.list().firstOrNull { it.id == id }
        if (entity != null && entity.groupId != 0L) {
            val remainingInGroup = manager.listByGroup(entity.groupId).size
            if (remainingInGroup <= 1) {
                val group = TabGroupStore.byId(requireContext(), entity.groupId)
                AlertDialog.Builder(requireContext())
                    .setTitle("Close last tab in group?")
                    .setMessage("Delete group \"${group?.name ?: "unnamed"}\" as well?")
                    .setPositiveButton("Delete group") { _, _ ->
                        TabGroupStore.delete(requireContext(), entity.groupId)
                        manager.close(id)
                        refresh(requireView())
                    }
                    .setNegativeButton("Keep group") { _, _ ->
                        manager.close(id)
                        refresh(requireView())
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
                refresh(requireView())
                if (currentTabs().isEmpty()) { dismiss(); onNew?.invoke() }
            } else {
                dismiss()
                onNew?.invoke()
            }
        } else {
            refresh(requireView())
        }
    }

    private fun confirmCloseAll() {
        val count = currentTabs().size
        if (count == 0) { dismiss(); return }
        val label = when (mode) {
            MODE_INCOGNITO -> "Close all $count incognito tabs?"
            else -> "Close all $count tabs?"
        }
        AlertDialog.Builder(requireContext())
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
                onNew?.invoke()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTabMenu(parentView: View) {
        val sheet = BottomSheetDialog(requireContext())
        val v = layoutInflater.inflate(R.layout.dialog_tab_menu, null)
        sheet.setContentView(v)
        sheet.behavior.isDraggable = false

        v.findViewById<View>(R.id.tmSelectTabs)?.setOnClickListener {
            sheet.dismiss(); toast("Select mode coming soon")
        }
        v.findViewById<View>(R.id.tmNewTab)?.setOnClickListener {
            sheet.dismiss()
            manager.openNew("about:blank")
            dismiss()
            onNew?.invoke()
        }
        v.findViewById<View>(R.id.tmNewIncognito)?.setOnClickListener {
            sheet.dismiss()
            manager.openNew("about:blank", incognito = true)
            dismiss()
            onNew?.invoke()
        }
        v.findViewById<View>(R.id.tmNewGroup)?.setOnClickListener {
            sheet.dismiss()
            promptCreateGroup(parentView)
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

    private fun promptCreateGroup(parentView: View) {
        val input = EditText(requireContext()).apply { hint = "Group name"; setSingleLine() }
        AlertDialog.Builder(requireContext())
            .setTitle("New tab group")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Group" }
                TabGroupStore.create(requireContext(), name)
                mode = MODE_GROUPS
                filterGroupId = 0L
                refresh(parentView)
                toast("Group '$name' created")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGroupMenu(groupId: Long) {
        val group = TabGroupStore.byId(requireContext(), groupId) ?: return
        AlertDialog.Builder(requireContext())
            .setTitle(group.name)
            .setItems(arrayOf("Open", "Rename", "Delete group")) { _, which ->
                when (which) {
                    0 -> {
                        filterGroupId = groupId
                        mode = MODE_GROUPS
                        refresh(requireView())
                    }
                    1 -> {
                        val input = EditText(requireContext()).apply { setText(group.name); setSingleLine() }
                        AlertDialog.Builder(requireContext())
                            .setTitle("Rename group")
                            .setView(input)
                            .setPositiveButton("Save") { _, _ ->
                                TabGroupStore.rename(requireContext(), groupId, input.text.toString().trim())
                                refresh(requireView())
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    2 -> {
                        TabGroupStore.delete(requireContext(), groupId)
                        refresh(requireView())
                    }
                }
            }
            .show()
    }

    private fun confirmDeleteData() {
        AlertDialog.Builder(requireContext())
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

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

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
