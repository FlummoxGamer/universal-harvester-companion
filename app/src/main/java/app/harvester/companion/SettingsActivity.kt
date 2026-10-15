package app.harvester.companion

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<android.widget.TextView>(R.id.settingsVersionLabel)?.text =
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        findViewById<android.view.View>(R.id.settingsSearchEngine).setOnClickListener {
            startActivity(Intent(this, SearchEngineActivity::class.java))
        }
        findViewById<android.view.View>(R.id.settingsAppearance).setOnClickListener {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }
        findViewById<android.view.View>(R.id.settingsScripts).setOnClickListener {
            startActivity(Intent(this, ScriptsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.settingsVideo).setOnClickListener {
            startActivity(Intent(this, VideoOptionsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.settingsDownloads).setOnClickListener {
            startActivity(Intent(this, DownloadSettingsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.settingsAccessibility).setOnClickListener {
            startActivity(Intent(this, AccessibilityActivity::class.java))
        }
        findViewById<android.view.View>(R.id.settingsCustomizeAddressBar).setOnClickListener {
            startActivity(Intent(this, CustomizeAddressBarActivity::class.java))
        }
        findViewById<android.view.View>(R.id.settingsPrivacyGuard).setOnClickListener {
            startActivity(Intent(this, PrivacyGuardActivity::class.java))
        }
        findViewById<android.view.View>(R.id.settingsLockAccess).setOnClickListener {
            Toast.makeText(this, "Lock Access coming soon", Toast.LENGTH_SHORT).show()
        }
        findViewById<android.view.View>(R.id.settingsManageData).setOnClickListener {
            startActivity(Intent(this, ManageDataActivity::class.java))
        }

        findViewById<android.view.View>(R.id.settingsVersion).setOnClickListener {
            Toast.makeText(
                this,
                "Harvester ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                Toast.LENGTH_LONG
            ).show()
        }
        findViewById<android.view.View>(R.id.settingsUpdate).setOnClickListener {
            UpdateChecker.checkAndPrompt(this)
        }
        findViewById<android.view.View>(R.id.settingsLogs).setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        UpdateChecker.resumePendingInstallIfPossible(this)
    }
}
