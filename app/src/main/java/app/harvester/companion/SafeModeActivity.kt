package app.harvester.companion

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SafeModeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_simple)

        findViewById<TextView>(R.id.simpleTitle).text = "Safe Mode"
        findViewById<android.view.View>(R.id.simpleBack).setOnClickListener { finish() }

        val content = findViewById<LinearLayout>(R.id.simpleContent)
        
        val crashDir = File(filesDir, "crashes")
        val crashFiles = crashDir.listFiles()?.sortedByDescending { it.lastModified() }
        val latestCrash = crashFiles?.firstOrNull()
        
        val logText = if (latestCrash != null && latestCrash.exists()) {
            latestCrash.readText()
        } else {
            "No crash log found."
        }

        val scroll = ScrollView(this)
        val tv = TextView(this)
        tv.text = logText
        tv.textSize = 12f
        tv.setPadding(16, 16, 16, 16)
        tv.setTextIsSelectable(true)
        scroll.addView(tv)
        content.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 550))

        val btnSave = Button(this).apply {
            text = "Save Crash Log"
            setOnClickListener {
                try {
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val fname = "uh_crash_$ts.txt"
                    val ok = DownloadHelper.saveTextToDownloads(this@SafeModeActivity, fname, logText)
                    if (ok) Toast.makeText(this@SafeModeActivity, "Saved to Downloads/$fname", Toast.LENGTH_LONG).show()
                    else Toast.makeText(this@SafeModeActivity, "Save failed", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@SafeModeActivity, "Save failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
        content.addView(btnSave)

        val btnRestartKeep = Button(this).apply {
            text = "Restart Browser (Keep Data)"
            setOnClickListener {
                stopAllProcesses()
                val prefs = getSharedPreferences("uh_safe_mode", MODE_PRIVATE)
                prefs.edit().putBoolean("force_safe_mode", false).apply()
                
                val bootMarker = File(filesDir, "boot_marker.tmp")
                if (bootMarker.exists()) bootMarker.delete()
                
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                finishAffinity()
                Process.killProcess(Process.myPid())
            }
        }
        content.addView(btnRestartKeep)

        val btnReset = Button(this).apply {
            text = "Clear Data & Restart"
            setOnClickListener {
                stopAllProcesses()
                val prefs = getSharedPreferences("uh_safe_mode", MODE_PRIVATE)
                prefs.edit().clear().apply()
                
                val bootMarker = File(filesDir, "boot_marker.tmp")
                if (bootMarker.exists()) bootMarker.delete()
                
                crashDir.deleteRecursively()
                
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                finishAffinity()
                Process.killProcess(Process.myPid())
            }
        }
        content.addView(btnReset)
        
        val btnExit = Button(this).apply {
            text = "Exit"
            setOnClickListener { finishAffinity() }
        }
        content.addView(btnExit)
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
