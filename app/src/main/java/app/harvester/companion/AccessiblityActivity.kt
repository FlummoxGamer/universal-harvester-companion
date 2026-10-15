package app.harvester.companion

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AccessibilityActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_simple)

        findViewById<TextView>(R.id.simpleTitle).text = "Accessibility"
        findViewById<View>(R.id.simpleBack).setOnClickListener { finish() }

        val c = findViewById<LinearLayout>(R.id.simpleContent)

        val scaleLabel = TextView(this).apply {
            text = "Text scaling: ${SettingsPrefs.getInt(this@AccessibilityActivity, SettingsPrefs.K_TEXT_SCALE, 100)}%"
            setTextColor(0xFFE6E9EE.toInt())
            textSize = 14f
            setPadding(16, 16, 16, 8)
        }
        c.addView(scaleLabel)

        val sb = SeekBar(this).apply {
            max = 45
            progress = SettingsPrefs.getInt(this@AccessibilityActivity, SettingsPrefs.K_TEXT_SCALE, 100) - 85
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val value = p + 85
                    scaleLabel.text = "Text scaling: $value%"
                    SettingsPrefs.setInt(this@AccessibilityActivity, SettingsPrefs.K_TEXT_SCALE, value)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            setPadding(16, 0, 16, 16)
        }
        c.addView(sb)

        val darkRow = layoutInflater.inflate(R.layout.item_tool_category, c, false)
        darkRow.findViewById<TextView>(R.id.rowLabel).text = "Force dark mode on all sites"
        val darkSw = darkRow.findViewById<Switch>(R.id.rowSwitch)
        darkSw.isChecked = SettingsPrefs.getBool(this, SettingsPrefs.K_FORCE_DARK_SITES, false)
        darkSw.setOnCheckedChangeListener { _, b ->
            SettingsPrefs.setBool(this, SettingsPrefs.K_FORCE_DARK_SITES, b)
        }
        c.addView(darkRow)
    }
}
