package app.harvester.companion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class CustomizeHomeActivity : AppCompatActivity() {

private lateinit var recentRecycler: RecyclerView
private lateinit var defaultContainer: LinearLayout
private lateinit var recentAdapter: WallpaperAdapter
private val genreAdapters = mutableListOf<WallpaperAdapter>()

private val categorizedWallpapers = linkedMapOf(
    "Anime" to listOf(
        "https://images.unsplash.com/photo-1578632767115-351597cf2477?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1541562232579-512a21360020?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1607604276583-eef5d076aa5f?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1581833971358-2c8b550f87b3?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1528360983277-13d401cdc186?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1493976040374-85c8e12f0c0e?q=80&w=2000&auto=format&fit=crop"
    ),
    "Cinematic" to listOf(
        "https://images.unsplash.com/photo-1478720568477-152d9b164e26?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1485846234645-a62644f84728?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1536440136628-849c177e76a1?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1517604931442-7e0c8ed2963c?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1440404653325-ab127d49abc1?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1485095329183-d0797cdc5676?q=80&w=2000&auto=format&fit=crop"
    ),
    "Aesthetic" to listOf(
        "https://images.unsplash.com/photo-1614850523459-c2f4c699c52e?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1550684848-fac1c5b4e853?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1579546929518-9e396f3cc809?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1554034483-04fda0d3507b?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1507608616759-54f48f0af0ee?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1557672172-298e090bd0f1?q=80&w=2000&auto=format&fit=crop"
    ),
    "Nature & Cozy" to listOf(
        "https://images.unsplash.com/photo-1441974231531-c6227db76b6e?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1472214103451-9374bd1c798e?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1470071459604-3b5ec3a7fe05?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1511497584788-876760111969?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1426604966848-d7adac402bff?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1501854140801-50d01698950b?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1497436072909-60f360e1d4b1?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1542273917363-3b1817f69a2d?q=80&w=2000&auto=format&fit=crop"
    )
)
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            Toast.makeText(this, "Processing image...", Toast.LENGTH_SHORT).show()
            Thread {
                val localPath = WallpaperStore.saveWallpaper(this, uri)
                runOnUiThread {
                    if (localPath != null) {
                        val intent = Intent(this, WallpaperPreviewActivity::class.java).apply {
                            putExtra("imagePath", localPath)
                            putExtra("isCustom", true)
                        }
                        startActivity(intent)
                    } else {
                        Toast.makeText(this, "Failed to save wallpaper", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customize_home)

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }

        recentRecycler = findViewById(R.id.recentRecycler)
        recentRecycler.layoutManager = GridLayoutManager(this, 4)

        defaultRecycler = findViewById(R.id.defaultRecycler)
        defaultRecycler.layoutManager = GridLayoutManager(this, 4)

        findViewById<android.view.View>(R.id.btnResetHome).setOnClickListener {
            val builder = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_UHCompanion)
            builder.setTitle("Reset Home?")
            builder.setMessage("Are you sure you want to reset all widgets and wallpapers to defaults?")
            builder.setPositiveButton("Confirm") { _, _ ->
                WallpaperStore.setCurrentWallpaper(this, "default", 0)
                ShortcutStore.clear(this)
                Toast.makeText(this, "Home reset to defaults", Toast.LENGTH_SHORT).show()
                finish()
            }
            builder.setNegativeButton("Cancel", null)
            builder.show()
        }

        loadWallpapers()
    }

    override fun onResume() {
        super.onResume()
        loadWallpapers()
    }

private fun loadWallpapers() {
    val recents = mutableListOf(WallpaperItem.Add, WallpaperItem.Default)
    recents.addAll(WallpaperStore.getRecent(this).map { WallpaperItem.Custom(it) })

    if (!::recentAdapter.isInitialized) {
        recentAdapter = WallpaperAdapter(recents, onClick = { item ->
            when (item) {
                is WallpaperItem.Add -> pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                is WallpaperItem.Default -> {
                    WallpaperStore.setCurrentWallpaper(this, "default", 0)
                    finish()
                }
                is WallpaperItem.Custom -> {
                    val intent = Intent(this, WallpaperPreviewActivity::class.java).apply {
                        putExtra("imagePath", item.uri)
                        putExtra("isCustom", true)
                    }
                    startActivity(intent)
                }
                is WallpaperItem.Remote -> {}
            }
        }, onLongClick = { item ->
            if (item is WallpaperItem.Custom) {
                showContextMenu(recentRecycler.findViewHolderForAdapterPosition(recents.indexOf(item))?.itemView, item.uri)
            }
        })
        recentRecycler.adapter = recentAdapter
    } else {
        recentAdapter.updateItems(recents)
    }

    val defaults = remoteWallpapers.map { WallpaperItem.Remote(it) }
    if (!::defaultAdapter.isInitialized) {
        defaultAdapter = WallpaperAdapter(defaults, onClick = { item ->
            if (item is WallpaperItem.Remote) {
                val intent = Intent(this, WallpaperPreviewActivity::class.java).apply {
                    putExtra("imageUrl", item.url)
                    putExtra("isCustom", false)
                }
                startActivity(intent)
            }
        }, onLongClick = { })
        defaultRecycler.adapter = defaultAdapter
    } else {
        defaultAdapter.updateItems(defaults)
    }
}

    private fun showContextMenu(anchor: android.view.View?, path: String) {
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

sealed class WallpaperItem {
    object Add : WallpaperItem()
    object Default : WallpaperItem()
    data class Custom(val uri: String) : WallpaperItem()
    data class Remote(val url: String) : WallpaperItem()
}

class WallpaperAdapter(
    private var items: List<WallpaperItem>,
    private val onClick: (WallpaperItem) -> Unit,
    private val onLongClick: (WallpaperItem) -> Unit
) : RecyclerView.Adapter<WallpaperAdapter.VH>() {

    fun updateItems(newItems: List<WallpaperItem>) {
        items = newItems
        notifyDataSetChanged()
    }
    class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.wpImage)
        val placeholder: ImageView = v.findViewById(R.id.wpPlaceholder)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_wallpaper_tile, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        h.placeholder.visibility = android.view.View.GONE
        h.image.visibility = android.view.View.GONE
        
        when (item) {
            is WallpaperItem.Add -> {
                h.placeholder.visibility = android.view.View.VISIBLE
                h.placeholder.setImageResource(R.drawable.ic_add)
            }
            is WallpaperItem.Default -> {
                h.placeholder.visibility = android.view.View.VISIBLE
                h.placeholder.setImageResource(R.drawable.ic_home)
            }
            is WallpaperItem.Custom -> {
                h.image.visibility = android.view.View.VISIBLE
                Thread {
                    try {
                        val bitmap = WallpaperStore.getSampledBitmap(item.uri, 300, 300)
                        if (bitmap != null) {
                            h.image.post { h.image.setImageBitmap(bitmap) }
                        }
                    } catch (_: Exception) {
                        h.image.post { h.image.setImageResource(R.drawable.ic_home) }
                    }
                }.start()
            }
            is WallpaperItem.Remote -> {
                h.image.visibility = android.view.View.VISIBLE
                Thread {
                    try {
                        val bmp = java.net.URL(item.url).openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
                        h.image.post { if (bmp != null) h.image.setImageBitmap(bmp) }
                    } catch (_: Exception) {}
                }.start()
            }
        }

        h.itemView.setOnClickListener { onClick(item) }
        h.itemView.setOnLongClickListener { onLongClick(item); true }
    }

    override fun getItemCount() = items.size
}
