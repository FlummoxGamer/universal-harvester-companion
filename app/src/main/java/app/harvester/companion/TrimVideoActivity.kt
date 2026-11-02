package app.harvester.companion

import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.RangeSlider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TrimVideoActivity : AppCompatActivity() {

    private lateinit var playerView: androidx.media3.ui.PlayerView
    private lateinit var exoPlayer: androidx.media3.exoplayer.ExoPlayer
    private lateinit var slider: RangeSlider
    private lateinit var tvDuration: TextView
    private lateinit var framesRecycler: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        setContentView(R.layout.activity_trim_video)

        val uriString = intent.getStringExtra("videoUri")
        if (uriString == null) { finish(); return }
        val videoUri = Uri.parse(uriString)

        playerView = findViewById(R.id.trimPlayerView)
        slider = findViewById(R.id.rangeSlider)
        tvDuration = findViewById(R.id.tvTrimDuration)
        framesRecycler = findViewById(R.id.framesRecycler)

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(this, videoUri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val durationSec = (durationMs / 1000).toFloat()
        retriever.release()

        slider.valueTo = durationSec
        slider.values = listOf(0f, kotlin.math.min(durationSec, 15f))
        updateDurationText()

        framesRecycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        
        lifecycleScope.launch(Dispatchers.IO) {
            val frames = extractFrames(videoUri, durationSec)
            withContext(Dispatchers.Main) {
                framesRecycler.adapter = FrameAdapter(frames)
            }
        }

        slider.addOnChangeListener { _, _, fromUser ->
            if (fromUser) {
                val start = slider.values[0]
                val end = slider.values[1]
                if (end - start > 15f) {
                    slider.values = listOf(start, start + 15f)
                }
                updateDurationText()
                exoPlayer.seekTo((slider.values[0] * 1000).toLong())
            }
        }

        exoPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(this).build()
        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                UHLog.e("video", "Trim preview error: ${error.message}")
                Toast.makeText(this@TrimVideoActivity, "Cannot play this video format", Toast.LENGTH_SHORT).show()
            }
        })
        
        playerView.player = exoPlayer
        val mediaItem = androidx.media3.common.MediaItem.fromUri(videoUri)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.volume = 0f
        exoPlayer.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
        exoPlayer.prepare()
        exoPlayer.play()

        findViewById<ImageButton>(R.id.btnCloseTrim).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnSaveTrim).setOnClickListener {
            val start = slider.values[0].toInt()
            val end = slider.values[1].toInt()
            processAndSaveVideo(videoUri, start, end)
        }
    }

    private fun extractFrames(uri: Uri, duration: Float): List<Bitmap> {
        val frames = mutableListOf<Bitmap>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val numFrames = 10
            val interval = (duration / numFrames).coerceAtLeast(1f)
            for (i in 0 until numFrames) {
                val timeUs = (i * interval * 1_000_000).toLong()
                val bmp = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 128, 128)
                if (bmp != null) frames.add(bmp)
            }
        } catch (_: Exception) {
        } finally {
            retriever.release()
        }
        return frames
    }

    private fun updateDurationText() {
        tvDuration.text = "Selected: ${slider.values[0].toInt()}s - ${slider.values[1].toInt()}s"
    }

    private fun processAndSaveVideo(uri: Uri, startSec: Int, endSec: Int) {
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

                val duration = endSec - startSec
                val cmd = "-y -ss $startSec -t $duration -i ${inputFile.absolutePath} -c:v libx264 -crf 23 -preset ultrafast ${outputFile.absolutePath}"
                
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
                            UHLog.e("video", "FFmpeg failed: ${session.failStackTrace} | Logs: ${session.allLogsAsString}")
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
    playerView.player = null
    exoPlayer.release()
    com.arthenica.ffmpegkit.FFmpegKitConfig.clearSessions()
}

    private class FrameAdapter(private val frames: List<Bitmap>) : RecyclerView.Adapter<FrameAdapter.VH>() {
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val image: ImageView = v.findViewById(R.id.frameImage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_trim_frame, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.image.setImageBitmap(frames[position])
        }

        override fun getItemCount() = frames.size
    }
}
