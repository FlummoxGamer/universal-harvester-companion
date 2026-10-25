package app.harvester.companion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

        findViewById<android.view.View>(R.id.btnSet).setOnClickListener {
            val currentPos = viewPager.currentItem
            if (currentPos in uris.indices) {
                val selectedUri = uris[currentPos]
                if (selectedUri == "default") {
                    WallpaperStore.setCurrentWallpaper(this, "default", 0)
                    finish()
                } else {
                    extractColorAndSave(selectedUri)
                }
            }
        }
    }

    private fun extractColorAndSave(uriString: String) {
        Toast.makeText(this, "Setting...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bitmap = if (uriString.startsWith("http")) {
                    java.net.URL(uriString).openStream().use { BitmapFactory.decodeStream(it) }
                } else {
                    contentResolver.openInputStream(Uri.parse(uriString))?.use { BitmapFactory.decodeStream(it) }
                }

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
                        WallpaperStore.setCurrentWallpaper(this@WallpaperPreviewActivity, uriString, textColor)
                        finish()
                    }
                } else {
                    withContext(Dispatchers.Main) { finish() }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    WallpaperStore.setCurrentWallpaper(this@WallpaperPreviewActivity, uriString, 0)
                    finish()
                }
            }
        }
    }

    inner class PreviewAdapter(private val items: List<String>) : RecyclerView.Adapter<PreviewAdapter.VH>() {
        inner class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
            val image: ImageView = v.findViewById(R.id.previewImage)
            val title: android.widget.TextView = v.findViewById(R.id.previewTitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_wallpaper_preview, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val item = items[position]
            if (item == "default") {
                h.image.setImageResource(android.R.color.transparent)
                h.image.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@WallpaperPreviewActivity, R.color.bg))
                h.title.setTextColor(androidx.core.content.ContextCompat.getColor(this@WallpaperPreviewActivity, R.color.accent))
            } else {
                Thread {
                    try {
                        val bitmap = if (item.startsWith("http")) {
                            java.net.URL(item).openStream().use { BitmapFactory.decodeStream(it) }
                        } else {
                            contentResolver.openInputStream(Uri.parse(item))?.use { BitmapFactory.decodeStream(it) }
                        }
                        if (bitmap != null) {
                            val palette = Palette.from(bitmap).generate()
                            val dominantColor = palette.getDominantColor(android.graphics.Color.GRAY)
                            val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(dominantColor)
                            val textColor = if (luminance > 0.5) android.graphics.Color.BLACK else android.graphics.Color.WHITE

                            h.image.post {
                                h.image.setImageBitmap(bitmap)
                                h.title.setTextColor(textColor)
                            }
                        }
                    } catch (_: Exception) {}
                }.start()
            }
        }

        override fun getItemCount() = items.size
    }
}
