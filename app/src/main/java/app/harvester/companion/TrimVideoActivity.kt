package app.harvester.companion

import android.app.ProgressDialog
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.RangeSlider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class TrimVideoActivity : AppCompatActivity() {

    private lateinit var playerView: androidx.media3.ui.PlayerView
    private lateinit var exoPlayer: androidx.media3.exoplayer.ExoPlayer
    private lateinit var slider: RangeSlider
    private lateinit var tvDuration: TextView
    private var videoUri: Uri? = null
    private var durationSec: Float = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        setContentView(R.layout.activity_trim_video)

        val uriString = intent.getStringExtra("videoUri")
        if (uriString == null) { finish(); return }
        videoUri = Uri.parse(uriString)

        playerView = findViewById(R.id.trimPlayerView)
        slider = findViewById(R.id.rangeSlider)
        tvDuration = findViewById(R.id.tvTrimDuration)

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(this, videoUri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        durationSec = (durationMs / 1000).toFloat()
        retriever.release()

        slider.valueTo = durationSec
        slider.values = listOf(0f, kotlin.math.min(durationSec, 10f))
        updateDurationText()

        slider.addOnChangeListener { _, _, _ -> updateDurationText() }

        exoPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(this).build()
        playerView.player = exoPlayer
        val mediaItem = androidx.media3.common.MediaItem.fromUri(videoUri)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.volume = 0f
        exoPlayer.prepare()
        exoPlayer.play()

        findViewById<ImageButton>(R.id.btnCloseTrim).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnSaveTrim).setOnClickListener {
            val start = slider.values[0].toInt()
            val end = slider.values[1].toInt()
            processAndSaveVideo(start, end)
        }
    }

    private fun updateDurationText() {
        tvDuration.text = "Selected: ${slider.values[0].toInt()}s - ${slider.values[1].toInt()}s"
    }

    private fun processAndSaveVideo(startSec: Int, endSec: Int) {
        val uri = videoUri ?: return
        val progress = ProgressDialog(this).apply {
            setMessage("Compressing video...")
            setCancelable(false)
            show()
        }

        val inputFile = File(cacheDir, "input_${System.currentTimeMillis()}.mp4")
        val outputFile = File(cacheDir, "output_${System.currentTimeMillis()}.mp4")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    inputFile.outputStream().use { output -> input.copyTo(output) }
                }

                val cmd = "-y -ss $startSec -to $endSec -i ${inputFile.absolutePath} -c:v libx264 -crf 23 -preset ultrafast ${outputFile.absolutePath}"
                
                com.arthenica.ffmpegkit.FFmpegKit.executeAsync(cmd) { session ->
                    val returnCode = session.returnCode
                    if (com.arthenica.ffmpegkit.ReturnCode.isSuccess(returnCode)) {
                        val finalPath = WallpaperStore.saveMotionWallpaperFile(this@TrimVideoActivity, outputFile)
                        runOnUiThread {
                            progress.dismiss()
                            if (finalPath != null) {
                                val intent = Intent(this@TrimVideoActivity, WallpaperPreviewActivity::class.java).apply {
                                    putExtra("imagePath", finalPath)
                                    putExtra("isCustom", true)
                                    putExtra("isVideo", true)
                                }
                                startActivity(intent)
                                finish()
                            } else {
                                Toast.makeText(this@TrimVideoActivity, "Failed to save video", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        runOnUiThread {
                            progress.dismiss()
                            UHLog.e("video", "FFmpeg failed: ${session.failStackTrace}")
                            Toast.makeText(this@TrimVideoActivity, "Compression failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                    inputFile.delete()
                    outputFile.delete()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.dismiss()
                    UHLog.e("video", "Video processing error: ${e.message}")
                    Toast.makeText(this@TrimVideoActivity, "Error processing video", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer.release()
    }
}
