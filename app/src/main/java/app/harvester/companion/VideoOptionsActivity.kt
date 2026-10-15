package app.harvester.companion

import android.os.Bundle
import android.view.View
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity

class VideoOptionsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_simple)
        findViewById<android.widget.TextView>(R.id.simpleTitle).text = "Video Options"
        findViewById<View>(R.id.simpleBack).setOnClickListener { finish() }
        val c = findViewById<android.widget.LinearLayout>(R.id.simpleContent)

        val row = layoutInflater.inflate(R.layout.item_tool_category, c, false)
        row.findViewById<android.widget.TextView>(R.id.rowLabel).text = "Autoplay videos"
        val sw = row.findViewById<Switch>(R.id.rowSwitch)
        sw.isChecked = SettingsPrefs.getBool(this, SettingsPrefs.K_AUTOPLAY, false)
        sw.setOnCheckedChangeListener { _, b ->
            SettingsPrefs.setBool(this, SettingsPrefs.K_AUTOPLAY, b)
        }
        c.addView(row)
    }
}
