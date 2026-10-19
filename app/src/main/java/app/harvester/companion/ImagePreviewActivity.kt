package app.harvester.companion

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.URL

class ImagePreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val imageView = ImageView(this)
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        imageView.setBackgroundColor(0xFF000000.toInt())
        setContentView(imageView)

        imageView.setOnClickListener { finish() }

        val url = intent.getStringExtra("url")
        if (url != null) {
            Thread {
                try {
                    val bmp = URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                    runOnUiThread {
                        if (bmp != null) imageView.setImageBitmap(bmp)
                        else {
                            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { finish() }
                }
            }.start()
        } else finish()
    }
}
