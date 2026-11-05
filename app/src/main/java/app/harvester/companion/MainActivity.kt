package app.harvester.companion

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import app.harvester.companion.browser.BrowserFragment
import app.harvester.companion.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var shortcutAdapter: ShortcutAdapter
    private var browserFragment: BrowserFragment? = null
    private var homeVideoPlayer: androidx.media3.exoplayer.ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            setTheme(R.style.Theme_UHCompanion)
            super.onCreate(savedInstanceState)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT

            val safeFlag = java.io.File(filesDir, "safe_mode.flag")
            if (safeFlag.exists()) {
                startActivity(Intent(this, SafeModeActivity::class.java))
                finish()
                return
            }
            initMain(savedInstanceState)
        } catch (e: Throwable) {
            UHLog.throwable("main", "MainActivity onCreate crashed.", e)
            java.io.File(filesDir, "safe_mode.flag").createNewFile()
            startActivity(Intent(this, SafeModeActivity::class.java))
            finish()
        }
    }

    private fun initMain(savedInstanceState: Bundle?) {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val testSafeModeButton = android.widget.Button(this).apply {
            id = android.view.View.generateViewId()
            text = "Test Safe Mode"
            setBackgroundColor(android.graphics.Color.parseColor("#457B9D"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                java.io.File(filesDir, "safe_mode.flag").createNewFile()
                app.harvester.companion.utils.phoenix.ProcessPhoenix.triggerRebirth(this@MainActivity)
            }
        }
        val params = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.WRAP_CONTENT,
            ConstraintLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            setMargins(16, 100, 16, 16)
        }
        binding.root.addView(testSafeModeButton, params)

        setupHomeGrid()
        setupBackDispatcher()
        handleIntent(intent)

        if (savedInstanceState != null) {
            binding.splashScreen.visibility = View.GONE
        }
    }

    private fun setupHomeGrid() {
        shortcutAdapter = ShortcutAdapter(
            onOpen = { s -> loadUrlFromHome(s.url) },
            onAdd = { showAddShortcutDialog() },
            onLongPress = { }
        )
        binding.shortcutsGrid.layoutManager = GridLayoutManager(this, 4)
        binding.shortcutsGrid.adapter = shortcutAdapter
        shortcutAdapter.submit(ShortcutStore.list(this))
        
        binding.btnEditHome.setOnClickListener {
            startActivity(Intent(this, CustomizeHomeActivity::class.java))
        }
    }

    private fun setupBackDispatcher() {
        onBackPressedDispatcher.addCallback(this) {
            if (binding.browserFragmentContainer.visibility == View.VISIBLE) {
                val fragment = supportFragmentManager.findFragmentById(R.id.browserFragmentContainer) as? BrowserFragment
                if (fragment != null) {
                    if (!fragment.onBackPressed()) {
                        supportFragmentManager.beginTransaction().remove(fragment).commit()
                        binding.browserFragmentContainer.visibility = View.GONE
                        binding.homeScreen.visibility = View.VISIBLE
                        binding.btnEditHome.visibility = View.VISIBLE
                    }
                } else {
                    binding.browserFragmentContainer.visibility = View.GONE
                    binding.homeScreen.visibility = View.VISIBLE
                    binding.btnEditHome.visibility = View.VISIBLE
                }
            } else {
                finish()
            }
        }
    }

    private fun showAddShortcutDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_shortcut_custom, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        view.findViewById<android.widget.TextView>(R.id.dialogTitle).text = "Add Shortcut"
        val urlInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputUrl)
        val nameInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputName)

        view.findViewById<View>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnSave).setOnClickListener {
            val raw = urlInput.text.toString().trim()
            if (raw.isBlank()) {
                urlInput.error = "URL is required"
                return@setOnClickListener
            }
            val fixed = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
            val manual = nameInput.text.toString().trim()
            val name = if (manual.isNotBlank()) manual else ShortcutStore.shortNameFor(fixed)
            
            ShortcutStore.add(this, Shortcut(name, fixed, ShortcutStore.iconUrlFor(fixed)))
            shortcutAdapter.submit(ShortcutStore.list(this))
            dialog.dismiss()
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun loadUrlFromHome(url: String) {
        binding.homeScreen.visibility = View.GONE
        binding.btnEditHome.visibility = View.GONE
        binding.browserFragmentContainer.visibility = View.VISIBLE
        
        val existing = supportFragmentManager.findFragmentById(R.id.browserFragmentContainer) as? BrowserFragment
        if (existing == null) {
            val fragment = BrowserFragment.newInstance(url)
            browserFragment = fragment
            supportFragmentManager.beginTransaction()
                .replace(R.id.browserFragmentContainer, fragment)
                .commitAllowingStateLoss()
        } else {
            existing.loadUrl(url)
        }
    }

    private fun handleIntent(intent: Intent?) {
        val url = intent?.data?.toString() ?: return
        if (url.startsWith("http")) {
            loadUrlFromHome(url)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        applyWallpaper()
        shortcutAdapter.submit(ShortcutStore.list(this))
        homeVideoPlayer?.play()
    }

    private fun applyWallpaper() {
        val wpPath = WallpaperStore.getCurrentWallpaper(this)
        val textColor = WallpaperStore.getTextColor(this)
        val isVideo = WallpaperStore.isCurrentVideo(this)
        val videoView = findViewById<androidx.media3.ui.PlayerView>(R.id.wallpaperVideo)
        val player = homeVideoPlayer

        if (isVideo && wpPath != null && wpPath != "default" && videoView != null) {
            binding.wallpaperBackground.visibility = View.GONE
            videoView.visibility = View.VISIBLE
            if (player == null) {
                homeVideoPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(this).build().also {
                    videoView.player = it
                    it.volume = 0f
                    it.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                }
            }
            homeVideoPlayer?.let {
                it.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(wpPath))))
                it.prepare()
                it.play()
            }
            binding.homeScreen.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            return
        } else if (videoView != null) {
            videoView.visibility = View.GONE
            homeVideoPlayer?.pause()
        }

        if (wpPath == null || wpPath == "default") {
            binding.wallpaperBackground.visibility = View.GONE
            binding.homeScreen.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
            binding.homeTitle.setTextColor(ContextCompat.getColor(this, R.color.accent))
            binding.btnEditHome.setImageResource(R.drawable.ic_pencil)
            binding.btnEditHome.setColorFilter(ContextCompat.getColor(this, R.color.text))
            binding.btnEditHome.background = null
            shortcutAdapter.setTextColor(ContextCompat.getColor(this, R.color.text), 0)
        } else {
            try {
                val bitmap = android.graphics.BitmapFactory.decodeFile(wpPath)
                if (bitmap != null) {
                    binding.wallpaperBackground.setImageBitmap(bitmap)
                    binding.wallpaperBackground.visibility = View.VISIBLE
                    binding.homeScreen.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            
                    val safeColor = if (textColor == 0) ContextCompat.getColor(this, R.color.text) else textColor
                    val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(safeColor)
                    val strictBW = if (luminance > 0.5) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                    val shadowColor = if (strictBW == android.graphics.Color.BLACK) android.graphics.Color.WHITE else android.graphics.Color.BLACK

                    binding.homeTitle.setTextColor(strictBW)
                    binding.homeTitle.setShadowLayer(8f, 0f, 2f, shadowColor)

                    val pencilDrawable = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_pencil)?.mutate()
                    pencilDrawable?.setTint(strictBW)
                    pencilDrawable?.setBounds(0, 0, pencilDrawable.intrinsicWidth, pencilDrawable.intrinsicHeight)

                    val padding = 16
                    val bmp = android.graphics.Bitmap.createBitmap(
                        (pencilDrawable?.intrinsicWidth ?: 48) + padding * 2, 
                        (pencilDrawable?.intrinsicHeight ?: 48) + padding * 2, 
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(bmp)

                    val mask = android.graphics.Bitmap.createBitmap(
                        pencilDrawable?.intrinsicWidth ?: 48, 
                        pencilDrawable?.intrinsicHeight ?: 48, 
                        android.graphics.Bitmap.Config.ALPHA_8
                    )
                    val maskCanvas = android.graphics.Canvas(mask)
                    pencilDrawable?.draw(maskCanvas)

                    val shadowPaint = android.graphics.Paint()
                    shadowPaint.setShadowLayer(8f, 0f, 2f, shadowColor)
                    canvas.drawBitmap(mask, padding.toFloat(), padding.toFloat(), shadowPaint)

                    pencilDrawable?.setBounds(padding, padding, padding + (pencilDrawable.intrinsicWidth), padding + (pencilDrawable.intrinsicHeight))
                    pencilDrawable?.draw(canvas)

                    binding.btnEditHome.setImageBitmap(bmp)
                    binding.btnEditHome.background = null
                    binding.btnEditHome.setPadding(0, 0, 0, 0)
                    binding.btnEditHome.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE

                    shortcutAdapter.setTextColor(strictBW, shadowColor)
                } else {
                    binding.wallpaperBackground.visibility = View.GONE
                    binding.homeScreen.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
                }
            } catch (_: Exception) {
                binding.wallpaperBackground.visibility = View.GONE
                binding.homeScreen.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
            }
        }
    }

    override fun onPause() {
        super.onPause()
        homeVideoPlayer?.pause()
        UHLog.onActivityPause(this)
    }

    override fun onDestroy() {
        val videoView = findViewById<androidx.media3.ui.PlayerView>(R.id.wallpaperVideo)
        videoView?.player = null
        homeVideoPlayer?.stop()
        homeVideoPlayer?.release()
        homeVideoPlayer = null
        super.onDestroy()
    }
}
