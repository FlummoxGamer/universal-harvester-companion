package app.harvester.companion

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            if (item is WallpaperItem.Custom) {
                val intent = Intent(this, WallpaperPreviewActivity::class.java).apply {
                    putExtra("imagePath", item.uri)
                    putExtra("isCustom", true)
                }
                startActivity(intent)
            } else if (item is WallpaperItem.Motion) {
                val intent = Intent(this, WallpaperPreviewActivity::class.java).apply {
                    putExtra("imagePath", item.uri)
                    putExtra("isCustom", true)
                    putExtra("isVideo", true)
                }
                startActivity(intent)
            }
        }, onLongClick = { item ->
            if (item is WallpaperItem.Custom || item is WallpaperItem.Motion) {
                val path = if (item is WallpaperItem.Custom) item.uri else (item as WallpaperItem.Motion).uri
                showContextMenu(recycler.findViewHolderForAdapterPosition(0)?.itemView, path)
            }
        })
        recycler.adapter = recentAdapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END, 0
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                return false
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
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Set Wallpaper")
        popup.menu.add(0, 2, 1, "Delete")
        popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val intent = Intent(this, WallpaperPreviewActivity::class.java).apply {
                        putExtra("imagePath", path)
                        putExtra("isCustom", true)
                        putExtra("isVideo", path.endsWith(".mp4"))
                    }
                    startActivity(intent)
                }
                2 -> {
                    WallpaperStore.deleteWallpaper(path)
                    loadWallpapers()
                }
            }
            true
        }
        popup.show()
    }
}
