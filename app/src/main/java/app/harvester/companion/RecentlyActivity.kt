package app.harvester.companion

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RecentlyActivity : AppCompatActivity() {

    private lateinit var recentAdapter: WallpaperAdapter
    private lateinit var recycler: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recently)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        recycler = findViewById(R.id.recentRecycler)
        recycler.layoutManager = GridLayoutManager(this, 4)

        recentAdapter = WallpaperAdapter(onClick = { item ->
            val path = when(item) {
                is WallpaperItem.Custom -> item.uri
                is WallpaperItem.Motion -> item.uri
                is WallpaperItem.Remote -> item.url
                else -> ""
            }
            if (path.isNotEmpty()) {
                val intent = Intent(this, WallpaperPreviewActivity::class.java).apply {
                    putExtra("imagePath", path)
                    putExtra("isCustom", item is WallpaperItem.Custom || item is WallpaperItem.Motion)
                    putExtra("isVideo", item is WallpaperItem.Motion)
                }
                startActivity(intent)
            }
        }, onLongClick = { item, anchor ->
            val path = when(item) {
                is WallpaperItem.Custom -> item.uri
                is WallpaperItem.Motion -> item.uri
                is WallpaperItem.Remote -> item.url
                else -> ""
            }
            if (path.isNotEmpty()) {
                showContextMenu(anchor, path)
            }
        })
        recycler.adapter = recentAdapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END, 0
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val fromPos = vh.adapterPosition
                val toPos = target.adapterPosition
                if (fromPos < 0 || toPos < 0) return false
                recentAdapter.moveItem(fromPos, toPos)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(recycler)

        loadWallpapers()
    }

    override fun onResume() {
        super.onResume()
        loadWallpapers()
        val activePath = WallpaperStore.getCurrentWallpaper(this) ?: "default"
        recentAdapter.setActiveWallpaper(activePath)
    }

    private fun loadWallpapers() {
        lifecycleScope.launch(Dispatchers.IO) {
            val recents = WallpaperStore.getRecent(this@RecentlyActivity).map { path ->
                if (path.endsWith(".mp4")) WallpaperItem.Motion(path) else WallpaperItem.Custom(path)
            }
            withContext(Dispatchers.Main) {
                recentAdapter.submitList(recents)
            }
        }
    }

    private fun showContextMenu(anchor: View?, path: String) {
        if (anchor == null) return
        val popupView = layoutInflater.inflate(R.layout.dialog_context_menu, null)
        val popup = android.widget.PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.elevation = 16f

        popupView.findViewById<View>(R.id.rowSetWallpaper).setOnClickListener {
            popup.dismiss()
            val intent = Intent(this, WallpaperPreviewActivity::class.java).apply {
                putExtra("imagePath", path)
                putExtra("isCustom", true)
                putExtra("isVideo", path.endsWith(".mp4"))
            }
            startActivity(intent)
        }

        popupView.findViewById<View>(R.id.rowDelete).setOnClickListener {
            popup.dismiss()
            WallpaperStore.deleteWallpaper(path)
            loadWallpapers()
        }

        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val x = location[0] + (anchor.width / 2) - (popupView.measuredWidth / 2)
        val y = location[1] + anchor.height - 16

        popup.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY, Math.max(0, x), y)
    }
}

class WallpaperAdapter(
    private val onClick: (WallpaperItem) -> Unit,
    private val onLongClick: (WallpaperItem, View) -> Unit
) : ListAdapter<WallpaperItem, WallpaperAdapter.VH>(DiffCallback) {

    private var activePath: String = "default"
    private var items = mutableListOf<WallpaperItem>()

    override fun submitList(list: List<WallpaperItem>?) {
        items = list?.toMutableList() ?: mutableListOf()
        super.submitList(list)
    }

    fun getItemAt(position: Int): WallpaperItem = items[position]

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                java.util.Collections.swap(items, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                java.util.Collections.swap(items, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
    }

    fun setActiveWallpaper(path: String) {
        if (activePath != path) {
            activePath = path
            notifyDataSetChanged()
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.wpImage)
        val placeholder: ImageView = v.findViewById(R.id.wpPlaceholder)
        val card: com.google.android.material.card.MaterialCardView = v.findViewById(R.id.wallpaperCard)
        val activeCheck: ImageView = v.findViewById(R.id.wpActiveCheck)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_wallpaper_tile, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = getItem(position)
        h.placeholder.visibility = View.GONE
        h.image.visibility = View.GONE
        h.activeCheck.visibility = View.GONE
        
        val isActive = when (item) {
            is WallpaperItem.Default -> activePath == "default"
            is WallpaperItem.Custom -> item.uri == activePath
            is WallpaperItem.Motion -> item.uri == activePath
            is WallpaperItem.Remote -> item.url == activePath
            else -> false
        }

        if (isActive) {
            h.card.strokeWidth = (4 * h.itemView.resources.displayMetrics.density).toInt()
            h.card.strokeColor = androidx.core.content.ContextCompat.getColor(h.itemView.context, R.color.accent)
            h.activeCheck.visibility = View.VISIBLE
        } else {
            h.card.strokeWidth = (1 * h.itemView.resources.displayMetrics.density).toInt()
            h.card.strokeColor = androidx.core.content.ContextCompat.getColor(h.itemView.context, R.color.border)
        }

        when (item) {
            is WallpaperItem.Add -> {
                h.placeholder.visibility = View.VISIBLE
                h.placeholder.setImageResource(R.drawable.ic_add)
            }
            is WallpaperItem.Default -> {
                h.placeholder.visibility = View.VISIBLE
                h.placeholder.setImageResource(R.drawable.ic_home)
            }
            is WallpaperItem.Custom -> {
                h.image.visibility = View.VISIBLE
                val imageToLoad: Any = if (item.uri.startsWith("http")) item.uri else File(item.uri)
                h.image.load(imageToLoad)
            }
            is WallpaperItem.Motion -> {
                h.image.visibility = View.VISIBLE
                val imageToLoad: Any = if (item.uri.startsWith("http")) item.uri else File(item.uri)
                h.image.load(imageToLoad) {
                    videoFrameMillis(1000)
                    crossfade(true)
                    error(R.drawable.ic_downloads)
                }
            }
            is WallpaperItem.Remote -> {
                h.image.visibility = View.VISIBLE
                if (item.url.endsWith(".mp4")) {
                    h.image.load(item.url) {
                        videoFrameMillis(1000)
                        crossfade(true)
                        error(R.drawable.ic_downloads)
                    }
                } else {
                    h.image.load(item.url) { crossfade(true) }
                }
            }
        }

        h.itemView.setOnClickListener { onClick(item) }
        h.itemView.setOnLongClickListener { onLongClick(item, h.itemView); true }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<WallpaperItem>() {
        override fun areItemsTheSame(oldItem: WallpaperItem, newItem: WallpaperItem): Boolean {
            if (oldItem::class != newItem::class) return false
            return when (oldItem) {
                is WallpaperItem.Custom -> oldItem.uri == (newItem as WallpaperItem.Custom).uri
                is WallpaperItem.Motion -> oldItem.uri == (newItem as WallpaperItem.Motion).uri
                is WallpaperItem.Remote -> oldItem.url == (newItem as WallpaperItem.Remote).url
                else -> true
            }
        }

        override fun areContentsTheSame(oldItem: WallpaperItem, newItem: WallpaperItem): Boolean {
            return oldItem == newItem
        }
    }
}
