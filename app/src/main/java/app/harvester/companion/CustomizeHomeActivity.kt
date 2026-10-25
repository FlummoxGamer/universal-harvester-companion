package app.harvester.companion

import android.content.Intent
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
import com.google.android.material.card.MaterialCardView

class CustomizeHomeActivity : AppCompatActivity() {

    private lateinit var recentRecycler: RecyclerView
    private lateinit var defaultRecycler: RecyclerView
    private lateinit var recentAdapter: WallpaperAdapter
    private lateinit var defaultAdapter: WallpaperAdapter

    private val remoteWallpapers = listOf(
        "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1550684848-fac1c5b4e853?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1507608616759-54f48f0af0ee?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1554034483-04fda0d3507b?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1614850523459-c2f4c699c52e?q=80&w=2000&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1579546929518-9e396f3cc809?q=80&w=2000&auto=format&fit=crop"
    )

private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri != null) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        val intent = Intent(this, WallpaperPreviewActivity::class.java).apply {
            putExtra("imageUri", uri.toString())
            putExtra("isCustom", true)
        }
        startActivity(intent)
    }
}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customize_home)

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }

        recentRecycler = findViewById(R.id.recentRecycler)
        recentRecycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        
        defaultRecycler = findViewById(R.id.defaultRecycler)
        defaultRecycler.layoutManager = GridLayoutManager(this, 4)

        findViewById<android.view.View>(R.id.btnResetHome).setOnClickListener {
            val builder = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_UHCompanion)
            builder.setTitle("Reset Home?")
            builder.setMessage("Are you sure you want to reset all home widgets and wallpapers to defaults?")
            builder.setPositiveButton("Confirm") { _, _ ->
                WallpaperStore.setCurrentWallpaper(this, "default", 0)
                ShortcutStore.clear(this)
                Toast.makeText(this, "Home reset", Toast.LENGTH_SHORT).show()
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

        recentAdapter = WallpaperAdapter(recents, onClick = { item ->
            when (item) {
                is WallpaperItem.Add -> pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                is WallpaperItem.Default -> {
                    WallpaperStore.setCurrentWallpaper(this, "default", 0)
                    finish()
                }
                is WallpaperItem.Custom -> {
                   val intent = Intent(this, WallpaperPreviewActivity::class.java).apply {
                       putExtra("imageUri", item.uri)
                    }
                    startActivity(intent)
                }
                is WallpaperItem.Remote -> {
                   val intent = Intent(this, WallpaperPreviewActivity::class.java).apply {
                       putExtra("imageUrl", item.url)
                }
                startActivity(intent)
            }
        }
        }, onLongClick = { item ->
            if (item is WallpaperItem.Custom) {
                showContextMenu(recentRecycler.findViewHolderForAdapterPosition(recents.indexOf(item))?.itemView, item.uri)
            }
        })
        recentRecycler.adapter = recentAdapter

val defaults = remoteWallpapers.map { WallpaperItem.Remote(it) }
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
    }

    private fun showContextMenu(anchor: android.view.View?, uri: String) {
        if (anchor == null) return
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Set Wallpaper")
        popup.menu.add(0, 2, 1, "Delete")
        popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val intent = Intent(this, WallpaperPreviewActivity::class.java).apply {
                        putExtra("imageUri", uri)
                    }
                    startActivity(intent)
                }
                2 -> {
                    WallpaperStore.removeRecent(this, uri)
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
    private val items: List<WallpaperItem>,
    private val onClick: (WallpaperItem) -> Unit,
    private val onLongClick: (WallpaperItem) -> Unit
) : RecyclerView.Adapter<WallpaperAdapter.VH>() {

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
                       val uri = android.net.Uri.parse(item.uri)
                       val bitmap = h.itemView.context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
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
