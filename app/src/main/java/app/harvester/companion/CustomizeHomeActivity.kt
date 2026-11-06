package app.harvester.companion

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.videoFrameMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CustomizeHomeActivity : AppCompatActivity() {

    private lateinit var recentRecycler: RecyclerView
    private lateinit var defaultContainer: LinearLayout
    private lateinit var recentAdapter: WallpaperAdapter
    private val genreAdapters = mutableListOf<WallpaperAdapter>()

    private val categorizedWallpapers = linkedMapOf(
        "Motion" to listOf(
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/motion/motion_1.mp4",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/motion/motion_2.mp4",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/motion/motion_3.mp4",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/motion/motion_4.mp4",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/motion/motion_5.mp4",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/motion/motion_6.mp4",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/motion/motion_7.mp4",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/motion/motion_8.mp4",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/motion/motion_9.mp4",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/motion/motion_10.mp4"
        ),
        "Anime" to listOf(
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_1.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_2.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_3.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_4.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_5.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_6.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_7.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_8.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_9.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_10.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_11.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_12.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_13.webp"
        ),
        "Cinematic" to listOf(
            "https://images.unsplash.com/photo-1478720568477-152d9b164e26?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1485846234645-a62644f84728?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1536440136628-849c177e76a1?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1517604931442-7e0c8ed2963c?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1440404653325-ab127d49abc1?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1485095329183-d0797cdc5676?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1478720568477-152d9b164e26?q=80&w=2000&auto=format&fit=crop"
        ),
        "Abstract" to listOf(
            "https://images.unsplash.com/photo-1614850523459-c2f4c699c52e?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1550684848-fac1c5b4e853?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1579546929518-9e396f3cc809?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1554034483-04fda0d3507b?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1507608616759-54f48f0af0ee?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1557672172-298e090bd0f1?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1604871000636-074fa5117945?q=80&w=2000&auto=format&fit=crop"
        ),
        "Liquid Metal" to listOf(
            "https://images.unsplash.com/photo-1614850523459-c2f4c699c52e?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1604871000636-074fa5117945?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1557672172-298e090bd0f1?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1614850715649-1d0106293bd1?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1550684848-fac1c5b4e853?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1620121692029-d088224ddc74?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1618005198919-d3d4b5a92ead?q=80&w=2000&auto=format&fit=crop"
        )
    )

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val mime = contentResolver.getType(uri)
            if (mime != null && mime.startsWith("video/")) {
                showTrimDialog(uri)
            } else {
                Toast.makeText(this, "Processing image...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val localPath = WallpaperStore.saveWallpaper(this@CustomizeHomeActivity, uri)
                    withContext(Dispatchers.Main) {
                        if (localPath != null) {
                            val intent = Intent(this@CustomizeHomeActivity, WallpaperPreviewActivity::class.java).apply {
                                putExtra("imagePath", localPath)
                                putExtra("isCustom", true)
                            }
                            startActivity(intent)
                        } else {
                            Toast.makeText(this@CustomizeHomeActivity, "Failed to save wallpaper", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

private fun showTrimDialog(uri: android.net.Uri) {
    val intent = Intent(this, TrimVideoActivity::class.java).apply {
        putExtra("videoUri", uri.toString())
    }
    startActivity(intent)
}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customize_home)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnOpenRecently).setOnClickListener {
            startActivity(Intent(this, RecentlyActivity::class.java))
        }

        recentRecycler = findViewById(R.id.recentRecycler)
        recentRecycler.layoutManager = GridLayoutManager(this, 4)
        
        defaultContainer = findViewById(R.id.defaultContainer)

        recentAdapter = WallpaperAdapter(onClick = { item ->
            when (item) {
                is WallpaperItem.Add -> pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                is WallpaperItem.Default -> {
                    WallpaperStore.setCurrentWallpaper(this@CustomizeHomeActivity, "default", 0, false)
                    finish()
                }
                is WallpaperItem.Custom -> {
                    val intent = Intent(this@CustomizeHomeActivity, WallpaperPreviewActivity::class.java).apply {
                        putExtra("imagePath", item.uri)
                        putExtra("isCustom", true)
                    }
                    startActivity(intent)
                }
                is WallpaperItem.Motion -> {
                    val intent = Intent(this@CustomizeHomeActivity, WallpaperPreviewActivity::class.java).apply {
                        putExtra("imagePath", item.uri)
                        putExtra("isCustom", true)
                        putExtra("isVideo", true)
                    }
                    startActivity(intent)
                }
                is WallpaperItem.Remote -> {}
            }
        }, onLongClick = { _, _ -> })
        recentRecycler.adapter = recentAdapter

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END, 0
        ) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val fromPos = viewHolder.adapterPosition
                val toPos = target.adapterPosition
                if (fromPos < 0 || toPos < 0) return false
                recentAdapter.moveItem(fromPos, toPos)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled(): Boolean = false
        }
        val touchHelper = ItemTouchHelper(callback)
        touchHelper.attachToRecyclerView(recentRecycler)

        recentRecycler.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private val handler = Handler(Looper.getMainLooper())
            private var dragRunnable: Runnable? = null
            private var editRunnable: Runnable? = null
            private var downX = 0f
            private var downY = 0f
            private var isDragReady = false
            private var isDragging = false
            private var isPopupShowing = false

            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
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
                                        if (item is WallpaperItem.Custom || item is WallpaperItem.Motion) {
                                            val path = if (item is WallpaperItem.Custom) item.uri else (item as WallpaperItem.Motion).uri
                                            showContextMenu(child, path)
                                        }
                                    }
                                }
                                handler.postDelayed(editRunnable!!, 500)
                            }
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
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
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        dragRunnable?.let { handler.removeCallbacks(it) }
                        dragRunnable = null
                        editRunnable?.let { handler.removeCallbacks(it) }
                        editRunnable = null
                    }
                }
                return false
            }
        })

        for ((genre, urls) in categorizedWallpapers) {
            val title = TextView(this).apply {
                text = genre
                setTextColor(androidx.core.content.ContextCompat.getColor(this@CustomizeHomeActivity, R.color.text))
                textSize = 18f
                val pad = (16 * resources.displayMetrics.density).toInt()
                val padTop = (24 * resources.displayMetrics.density).toInt()
                val padBottom = (8 * resources.displayMetrics.density).toInt()
                setPadding(pad, padTop, pad, padBottom)
                typeface = androidx.core.content.res.ResourcesCompat.getFont(this@CustomizeHomeActivity, R.font.app_font)
            }
            defaultContainer.addView(title)

            val rv = RecyclerView(this)
            rv.layoutManager = GridLayoutManager(this, 4)
            rv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val rvPad = (16 * resources.displayMetrics.density).toInt()
            rv.setPadding(rvPad, 0, rvPad, 0)
            rv.clipToPadding = false
            rv.isNestedScrollingEnabled = false

            val adapter = WallpaperAdapter(onClick = { item ->
                if (item is WallpaperItem.Remote) {
                    val isVideo = genre == "Motion"
                    val intent = Intent(this, WallpaperPreviewActivity::class.java).apply {
                        putExtra("imageUrl", item.url)
                        putExtra("isCustom", false)
                        putExtra("isVideo", isVideo)
                    }
                    startActivity(intent)
                }
            }, onLongClick = { _, _ -> })
            rv.adapter = adapter
            genreAdapters.add(adapter)
            defaultContainer.addView(rv)
            
            adapter.submitList(urls.map { WallpaperItem.Remote(it) })
        }

        findViewById<View>(R.id.btnResetHome).setOnClickListener {
            val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            builder.setTitle("Reset Home?")
            builder.setMessage("Are you sure you want to reset all widgets and wallpapers to defaults?")
            builder.setPositiveButton("Confirm") { _, _ ->
                WallpaperStore.setCurrentWallpaper(this, "default", 0, false)
                ShortcutStore.clear(this)
                Toast.makeText(this, "Home reset to defaults", Toast.LENGTH_SHORT).show()
                finish()
            }
            builder.setNegativeButton("Cancel", null)
            val dialog = builder.create()
            dialog.show()
            val width = (resources.displayMetrics.widthPixels * 0.90).toInt()
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        loadWallpapers()
    }

    override fun onResume() {
        super.onResume()
        loadWallpapers()
        val activePath = WallpaperStore.getCurrentWallpaper(this) ?: "default"
        recentAdapter.setActiveWallpaper(activePath)
        for (adapter in genreAdapters) {
            adapter.setActiveWallpaper(activePath)
        }
    }

    private fun loadWallpapers() {
        lifecycleScope.launch(Dispatchers.IO) {
            val recents = mutableListOf(WallpaperItem.Add, WallpaperItem.Default)
            val storedRecents = WallpaperStore.getRecent(this@CustomizeHomeActivity).take(2)
            recents.addAll(storedRecents.map { 
                if (it.endsWith(".mp4")) WallpaperItem.Motion(it) else WallpaperItem.Custom(it) 
            })

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
            val isVideo = path.endsWith(".mp4")
            val intent = Intent(this, WallpaperPreviewActivity::class.java).apply {
                putExtra("imagePath", path)
                putExtra("isCustom", true)
                putExtra("isVideo", isVideo)
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

sealed class WallpaperItem {
    object Add : WallpaperItem()
    object Default : WallpaperItem()
    data class Custom(val uri: String) : WallpaperItem()
    data class Motion(val uri: String) : WallpaperItem()
    data class Remote(val url: String) : WallpaperItem()
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
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_wallpaper_tile, parent, false)
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
                    videoFrameMillis(1000L)
                    crossfade(true)
                    error(R.drawable.ic_downloads)
                }
            }
                is WallpaperItem.Remote -> {
                    h.image.visibility = View.VISIBLE
                    if (item.url.endsWith(".mp4")) {
                        h.image.load(item.url) {
                            videoFrameMillis(1000L)
                            crossfade(true)
                            error(R.drawable.ic_downloads)
                        }
                } else {
                    h.image.load(item.url) {
                        crossfade(true)
                    }
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
