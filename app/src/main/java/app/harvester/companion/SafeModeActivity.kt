package app.harvester.companion

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SafeModeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@SafeModeActivity, R.color.bg))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        
        val title = TextView(this).apply {
            text = "Safe Mode"
            setTextColor(ContextCompat.getColor(this@SafeModeActivity, R.color.text))
            textSize = 26f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "The app crashed on the last launch. Choose a recovery option below."
            setTextColor(ContextCompat.getColor(this@SafeModeActivity, R.color.subtext))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
        root.addView(subtitle)

        val crashDir = File(filesDir, "crashes")
        val crashFiles = crashDir.listFiles()?.sortedByDescending { it.lastModified() }
        val latestCrash = crashFiles?.firstOrNull()
        val logText = if (latestCrash != null && latestCrash.exists()) latestCrash.readText() else "No crash log found."

        val tv = TextView(this).apply {
            text = logText
            setTextColor(ContextCompat.getColor(this@SafeModeActivity, R.color.text))
            textSize = 12f
            setPadding(0, 16, 0, 32)
            setTextIsSelectable(true)
        }
        root.addView(tv)

        val btnSafeRestart = createButton("Restart App (Keep Data)", R.color.surface)
        btnSafeRestart.setOnClickListener {
            stopAllProcesses()
            getSharedPreferences("uh_safe_mode", MODE_PRIVATE).edit().clear().apply()
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finishAffinity()
            Process.killProcess(Process.myPid())
        }
        root.addView(btnSafeRestart)

        val btnClearCache = createButton("Clear Cache & Restart", R.color.accent)
        btnClearCache.setTextColor(ContextCompat.getColor(this, R.color.bg))
        btnClearCache.setOnClickListener {
            stopAllProcesses()
            try {
                cacheDir.deleteRecursively()
            } catch (_: Exception) {}
            getSharedPreferences("uh_safe_mode", MODE_PRIVATE).edit().clear().apply()
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finishAffinity()
            Process.killProcess(Process.myPid())
        }
        root.addView(btnClearCache)

        val btnClearData = createButton("Clear App Data (Nuclear)", R.color.red)
        btnClearData.setTextColor(ContextCompat.getColor(this, R.color.bg))
        btnClearData.setOnClickListener {
            stopAllProcesses()
            getSharedPreferences("uh_safe_mode", MODE_PRIVATE).edit().clear().apply()
            try {
                cacheDir.deleteRecursively()
                filesDir.deleteRecursively()
                getExternalFilesDir(null)?.deleteRecursively()
            } catch (_: Exception) {}
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finishAffinity()
            Process.killProcess(Process.myPid())
        }
        root.addView(btnClearData)

        val btnSaveLog = createButton("Export Crash Log", R.color.surface)
        btnSaveLog.setOnClickListener {
            try {
                if (latestCrash != null && latestCrash.exists()) {
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val fname = "uh_crash_$ts.txt"
                    val ok = DownloadHelper.saveTextToDownloads(this, fname, logText)
                    Toast.makeText(this, if (ok) "Saved to Downloads" else "Save failed", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No crash log found", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {}
        }
        root.addView(btnSaveLog)

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun createButton(text: String, bgColorRes: Int): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(ContextCompat.getColor(this@SafeModeActivity, R.color.text))
            setBackgroundColor(ContextCompat.getColor(this@SafeModeActivity, bgColorRes))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 }
        }
    }

    private fun stopAllProcesses() {
        try {
            DownloadStore.activeDownloads.value.forEach { DownloadEngine.cancelDownload(this, it.id) }
            stopService(Intent(this, DownloadService::class.java))
            stopService(Intent(this, SessionService::class.java))
            WorkManager.getInstance(this).cancelAllWork()
        } catch (_: Exception) {}
    }
}
