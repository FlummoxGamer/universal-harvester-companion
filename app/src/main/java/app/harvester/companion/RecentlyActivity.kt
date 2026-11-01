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
    val x = location[0]
    val y = location[1] + anchor.height - 16

    popup.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY, x, y)
}
}
