package app.harvester.companion

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class UpdateInstallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_PATH = "apk_path"
        private const val SESSION_ID = 0x5548
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        if (apkPath.isNullOrBlank()) {
            Toast.makeText(this, "Update file missing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val apk = File(apkPath)
        if (!apk.exists() || apk.length() == 0L) {
            Toast.makeText(this, "Update file not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        try {
            installViaSession(apk)
        } catch (e: Exception) {
            UHLog.throwable("update", "session install failed", e)
            Toast.makeText(this, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun installViaSession(apk: File) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(packageName)
        }

        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)

        session.use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { input ->
                    input.copyTo(out)
                }
            }

            val callbackIntent = Intent(this, UpdateInstallReceiver::class.java).apply {
                action = UpdateInstallReceiver.ACTION_INSTALL_RESULT
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending = PendingIntent.getBroadcast(
                this, sessionId, callbackIntent, flags
            )
            s.commit(pending.intentSender)
        }
    }
}
