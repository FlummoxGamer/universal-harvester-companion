package app.harvester.companion

import android.os.Bundle
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class AdvancedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced)

        findViewById<android.view.View>(R.id.advBack).setOnClickListener { finish() }

        bindSwitch(R.id.advSafeBrowsing, SettingsPrefs.K_ADV_SAFE_BROWSING)
        bindSwitch(R.id.advDnt, SettingsPrefs.K_ADV_DNT)
        bindSwitch(R.id.advDeamp, SettingsPrefs.K_ADV_DEAMP)
        bindSwitch(R.id.advGpc, SettingsPrefs.K_ADV_GPC)
        bindSwitch(R.id.advSecureDns, SettingsPrefs.K_ADV_SECURE_DNS)

        findViewById<android.view.View>(R.id.advWebRtc).setOnClickListener {
            Toast.makeText(this, "WebRTC IP Protection coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindSwitch(id: Int, key: String) {
        val sw = findViewById<Switch>(id)
        sw.isChecked = SettingsPrefs.getBool(this, key, false)
        sw.setOnCheckedChangeListener { _, checked ->
            SettingsPrefs.setBool(this, key, checked)
        }
    }
}
