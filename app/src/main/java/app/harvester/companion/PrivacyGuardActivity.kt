package app.harvester.companion

import android.content.Intent
import android.os.Bundle
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class PrivacyGuardActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy_guard)

        findViewById<android.view.View>(R.id.pgBack).setOnClickListener { finish() }

        bindSwitch(R.id.pgAdsSwitch, SettingsPrefs.K_PG_ADS)
        bindSwitch(R.id.pgHttpsSwitch, SettingsPrefs.K_PG_HTTPS)
        bindSwitch(R.id.pgFingerprintSwitch, SettingsPrefs.K_PG_FINGERPRINT)
        bindSwitch(R.id.pgScriptSwitch, SettingsPrefs.K_PG_SCRIPT)

        findViewById<android.view.View>(R.id.pgCookies).setOnClickListener { showCookieDialog() }
        findViewById<android.view.View>(R.id.pgRedirects).setOnClickListener { showRedirectDialog() }
        findViewById<android.view.View>(R.id.pgSiteSettings).setOnClickListener {
            Toast.makeText(this, "Site Settings coming soon", Toast.LENGTH_SHORT).show()
        }
        findViewById<android.view.View>(R.id.pgAdvanced).setOnClickListener {
            startActivity(Intent(this, AdvancedActivity::class.java))
        }
    }

    private fun bindSwitch(id: Int, key: String) {
        val sw = findViewById<Switch>(id)
        sw.isChecked = SettingsPrefs.getBool(this, key, false)
        sw.setOnCheckedChangeListener { _, checked ->
            SettingsPrefs.setBool(this, key, checked)
        }
    }

    private fun showCookieDialog() {
        val opts = arrayOf("Don't block any cookies", "Block third-party cookies only")
        val current = SettingsPrefs.getInt(this, SettingsPrefs.K_PG_COOKIES, 0)
        AlertDialog.Builder(this)
            .setTitle("Cookie Management")
            .setSingleChoiceItems(opts, current) { dialog, which ->
                SettingsPrefs.setInt(this, SettingsPrefs.K_PG_COOKIES, which)
                dialog.dismiss()
            }
            .show()
    }

    private fun showRedirectDialog() {
        val opts = arrayOf("Stop redirecting to app", "Ask before redirecting", "Always allow")
        val current = SettingsPrefs.getInt(this, SettingsPrefs.K_PG_REDIRECT, 1)
        AlertDialog.Builder(this)
            .setTitle("Stop App Redirects")
            .setSingleChoiceItems(opts, current) { dialog, which ->
                SettingsPrefs.setInt(this, SettingsPrefs.K_PG_REDIRECT, which)
                dialog.dismiss()
            }
            .show()
    }
}
