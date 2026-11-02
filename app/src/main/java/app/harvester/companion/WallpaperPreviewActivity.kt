package app.harvester.companion

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toBitmap
import coil.load
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WallpaperPreviewActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private var bitmaps = mutableListOf<Bitmap>()
    private var uris = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallpaper_preview)

        viewPager = findViewById(R.id.viewPager)
        findViewById<android.view.View>(R.id.btnClose).setOnClickListener { finish() }

val initialPath = intent.getStringExtra("imagePath")
val initialUrl = intent.getStringExtra("imageUrl")

val isCustom = intent.getBooleanExtra("isCustom", true)
val recent = mutableListOf<String>()
if (isCustom) {
    recent.addAll(WallpaperStore.getRecent(this))
    if (initialPath != null) {
        recent.remove(initialPath)
        recent.add(0, initialPath)
    }
} else {
    recent.add(initialUrl ?: "default")
}
if (recent.isEmpty()) {
    recent.add("default")
}

        uris = recent
        viewPager.adapter = PreviewAdapter(uris)

        val initialIndex = uris.indexOf(initialPath)
        if (initialIndex >= 0) {
            viewPager.setCurrentItem(initialIndex, false)
        }

findViewById<android.view.View>(R.id.btnSet).setOnClickListener {
    val currentPos = viewPager.currentItem
    if (currentPos in uris.indices) {
        val selectedUri = uris[currentPos]
        if (selectedUri == "default") {
            WallpaperStore.setCurrentWallpaper(this, "default", 0, false)
            finish()
        } else {
            val recyclerView = viewPager.getChildAt(0) as? RecyclerView
            val viewHolder = recyclerView?.findViewHolderForAdapterPosition(currentPos)
            val touchImage = viewHolder?.itemView?.findViewById<app.harvester.companion.TouchImageView>(R.id.previewImage)
            if (touchImage != null) {
                val zoom = touchImage.currentZoom
                val scroll = touchImage.scrollPosition
                WallpaperStore.setWallpaperTransform(this, zoom, scroll.x, scroll.y)
            }
            extractColorAndSave(selectedUri)
        }
    }
}
    }

    private fun extractColorAndSave(uriString: String) {
        Toast.makeText(this, "Applying wallpaper...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                var finalUriString = uriString
                if (uriString.startsWith("http")) {
                    val downloadedPath = WallpaperStore.downloadRemoteWallpaper(this@WallpaperPreviewActivity, uriString)
                    if (downloadedPath != null) {
                        finalUriString = downloadedPath
                    }
                }
                val bitmap = BitmapFactory.decodeFile(finalUriString)

                if (bitmap != null) {
                    val palette = Palette.from(bitmap).generate()
                    val dominantColor = palette.getDominantColor(android.graphics.Color.GRAY)
                    
                    val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(dominantColor)
                    val textColor = if (luminance > 0.5) {
                        android.graphics.Color.BLACK
                    } else {
                        android.graphics.Color.WHITE
                    }

                    withContext(Dispatchers.Main) {
                        WallpaperStore.setCurrentWallpaper(this@WallpaperPreviewActivity, finalUriString, textColor)
                        finish()
                    }
                } else {
                    withContext(Dispatchers.Main) { finish() }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    WallpaperStore.setCurrentWallpaper(this@WallpaperPreviewActivity, "default", 0)
                    finish()
                }
            }
        }
    }

override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
    super.onConfigurationChanged(newConfig)
    viewPager.adapter?.notifyDataSetChanged()
    val currentPos = viewPager.currentItem
    viewPager.setCurrentItem(currentPos, false)
}

inner class PreviewAdapter(private val items: List<String>) : RecyclerView.Adapter<PreviewAdapter.VH>() {

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        val player = holder.itemView.tag as? androidx.media3.exoplayer.ExoPlayer
        player?.release()
        holder.itemView.tag = null
    }
    inner class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.previewImage)
        val title: android.widget.TextView = v.findViewById(R.id.previewTitle)
        val shortcuts: androidx.recyclerview.widget.RecyclerView = v.findViewById(R.id.previewShortcuts)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = layoutInflater.inflate(R.layout.item_wallpaper_preview, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        
        val shortcutList = ShortcutStore.list(this@WallpaperPreviewActivity)
        val adapter = ShortcutAdapter(
            onOpen = {},
            onAdd = {},
            onLongPress = {}
        )
        h.shortcuts.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@WallpaperPreviewActivity, 4)
        h.shortcuts.adapter = adapter
        adapter.submit(shortcutList)

        val surfaceColor = com.google.android.material.color.MaterialColors.getColor(h.itemView, com.google.android.material.R.attr.colorSurface)
        val onSurfaceColor = com.google.android.material.color.MaterialColors.getColor(h.itemView, com.google.android.material.R.attr.colorOnSurface)
        val bgColor = com.google.android.material.color.MaterialColors.getColor(h.itemView, androidx.appcompat.R.attr.colorBackgroundFloating)

        val touchImage = h.itemView.findViewById<app.harvester.companion.TouchImageView>(R.id.previewImage)
        touchImage?.minZoom = app.harvester.companion.TouchImageView.AUTOMATIC_MIN_ZOOM
        touchImage?.maxZoom = 3f
        touchImage?.isZoomEnabled = true

        val mockAddressBar = h.itemView.findViewById<LinearLayout>(R.id.previewMockAddressBar)
        mockAddressBar?.setBackgroundColor(surfaceColor)
        val mockUrlText = h.itemView.findViewById<TextView>(R.id.previewMockUrlText)
        mockUrlText?.setTextColor(onSurfaceColor)
        val mockTabCount = h.itemView.findViewById<TextView>(R.id.previewMockTabCount)
        mockTabCount?.setTextColor(onSurfaceColor)

        if (item == "default") {
            h.image.setImageResource(android.R.color.transparent)
            h.image.setBackgroundColor(bgColor)
            h.title.setTextColor(onSurfaceColor)
            adapter.setTextColor(onSurfaceColor, 0)
        } else {
            val isVideo = item.endsWith(".mp4") || item.contains("sample")
            val playerView = h.itemView.findViewById<androidx.media3.ui.PlayerView>(R.id.previewVideo)
            if (isVideo) {
                h.image.visibility = View.GONE
                playerView.visibility = View.VISIBLE
                val exoPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(this@WallpaperPreviewActivity).build()
                playerView.player = exoPlayer
                val mediaUri: android.net.Uri = if (item.startsWith("http")) android.net.Uri.parse(item) else android.net.Uri.fromFile(java.io.File(item))
                val mediaItem = androidx.media3.common.MediaItem.Builder()
                    .setUri(mediaUri)
                    .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MP4)
                    .build()
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.volume = 0f
                exoPlayer.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        UHLog.e("video", "ExoPlayer Error: ${error.message}")
                    }
                })
                exoPlayer.prepare()
                exoPlayer.play()
                h.itemView.tag = exoPlayer
                h.title.setTextColor(android.graphics.Color.WHITE)
                adapter.setTextColor(android.graphics.Color.WHITE, android.graphics.Color.BLACK)
                return
            } else {
                playerView.visibility = View.GONE
                val oldPlayer = h.itemView.tag as? androidx.media3.exoplayer.ExoPlayer
                oldPlayer?.release()
                h.itemView.tag = null
            }

            val imageToLoad: Any = if (item.startsWith("http")) item else java.io.File(item)
            h.image.load(imageToLoad) {
                crossfade(true)
                allowHardware(false)
                listener(
                    onSuccess = { _, result ->
                        val bitmap = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        if (bitmap != null) {
                            val palette = Palette.from(bitmap).generate()
                            val dominantColor = palette.getDominantColor(android.graphics.Color.GRAY)
                            val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(dominantColor)
                            val textColor = if (luminance > 0.5) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                            val shadowColor = if (textColor == android.graphics.Color.BLACK) android.graphics.Color.WHITE else android.graphics.Color.BLACK
       
                            h.title.setTextColor(textColor)
                            h.title.setShadowLayer(8f, 0f, 2f, shadowColor)
                            adapter.setTextColor(textColor, shadowColor)
                       }
                  },
                  onError = { _, result ->
                      UHLog.e("preview", "Coil error: ${result.throwable.message}")
                  }
               )
            }
        }
    }

    override fun getItemCount() = items.size
}
}
