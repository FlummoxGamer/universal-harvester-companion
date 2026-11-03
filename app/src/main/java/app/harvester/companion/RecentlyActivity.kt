package app.harvester.companion

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.videoFrameMillis
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
    override fun isLongPressDragEnabled(): Boolean = false
})
touchHelper.attachToRecyclerView(recycler)

recycler.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var dragRunnable: Runnable? = null
    private var editRunnable: Runnable? = null
    private var downX = 0f
    private var downY = 0f
    private var isDragReady = false
    private var isDragging = false
    private var isPopupShowing = false

    override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
        when (e.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                isDragReady = false
                isDragging = false
                isPopupShowing = false
                val child = rv.findChildViewUnder(e.x, e.y)
                if (child != null) {
                    val pos = rv.getChildAdapterPosition(child)
                    if (pos in 0 until recentAdapter.itemCount) {
                        dragRunnable = Runnable { isDragReady = true }
                        handler.postDelayed(dragRunnable!!, 150)

                        editRunnable = Runnable {
                            if (!isDragging && !isPopupShowing) {
                                isPopupShowing = true
                                val item = recentAdapter.getItemAt(pos)
                                val path = when(item) {
                                    is WallpaperItem.Custom -> item.uri
                                    is WallpaperItem.Motion -> item.uri
                                    is WallpaperItem.Remote -> item.url
                                    else -> ""
                                }
                                if (path.isNotEmpty()) showContextMenu(child, path)
                            }
                        }
                        handler.postDelayed(editRunnable!!, 500)
                    }
                }
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (isPopupShowing) return false
                val dx = Math.abs(e.x - downX)
                val dy = Math.abs(e.y - downY)
                if (dx > 20 || dy > 20) {
                    editRunnable?.let { handler.removeCallbacks(it) }
                    editRunnable = null
                    
                    if (isDragReady && !isDragging) {
                        isDragging = true
                        val child = rv.findChildViewUnder(downX, downY)
                        if (child != null) {
                            val pos = rv.getChildAdapterPosition(child)
                            if (pos in 0 until recentAdapter.itemCount) {
                                touchHelper.startDrag(rv.findViewHolderForAdapterPosition(pos)!!)
                            }
                        }
                    } else if (!isDragReady) {
                        dragRunnable?.let { handler.removeCallbacks(it) }
                        dragRunnable = null
                    }
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                dragRunnable?.let { handler.removeCallbacks(it) }
                dragRunnable = null
                editRunnable?.let { handler.removeCallbacks(it) }
                editRunnable = null
            }
        }
        return false
    }
})

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
        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        
        val popup = android.widget.PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.elevation = 16f
        popup.width = (240 * resources.displayMetrics.density).toInt()

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
