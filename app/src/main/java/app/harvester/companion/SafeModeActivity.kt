package app.harvester.companion

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SafeModeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_safe_mode)

        val crashDir = File(filesDir, "crashes")
        val crashFiles = crashDir.listFiles()?.sortedByDescending { it.lastModified() }
        val latestCrash = crashFiles?.firstOrNull()
        val logText = if (latestCrash != null && latestCrash.exists()) latestCrash.readText() else "No crash log found."
        
        findViewById<TextView>(R.id.safeModeLogText).text = logText

        findViewById<MaterialButton>(R.id.btnSafeRestart).setOnClickListener {
            stopAllProcesses()
            clearCrashState()
            restartApp()
        }

        findViewById<MaterialButton>(R.id.btnClearCache).setOnClickListener {
            stopAllProcesses()
            try { cacheDir.deleteRecursively() } catch (_: Exception) {}
            clearCrashState()
            restartApp()
        }

        findViewById<MaterialButton>(R.id.btnClearData).setOnClickListener {
            stopAllProcesses()
            try {
                cacheDir.deleteRecursively()
                filesDir.deleteRecursively()
                getExternalFilesDir(null)?.deleteRecursively()
            } catch (_: Exception) {}
                clearCrashState()
                restartApp()
         }

        findViewById<MaterialButton>(R.id.btnExportLog).setOnClickListener {
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
    }

    private fun clearCrashState() {
        try {
            val crashFile = File(filesDir, "last_crash.txt")
            if (crashFile.exists()) crashFile.delete()
            val crashDir = File(filesDir, "crashes")
            crashDir.deleteRecursively()
        } catch (_: Exception) {}
        getSharedPreferences("uh_safe_mode", MODE_PRIVATE).edit().clear().apply()
    }

    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finishAffinity()
        Process.killProcess(Process.myPid())
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
