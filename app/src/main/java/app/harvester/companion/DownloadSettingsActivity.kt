package app.harvester.companion

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity

class DownloadSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_simple)

        findViewById<android.widget.TextView>(R.id.simpleTitle).text = "Download Settings"
        findViewById<View>(R.id.simpleBack).setOnClickListener { finish() }

        val c = findViewById<LinearLayout>(R.id.simpleContent)

        val askRow = layoutInflater.inflate(R.layout.item_tool_category, c, false)
        askRow.findViewById<android.widget.TextView>(R.id.rowLabel).text = "Ask Before Downloading"
        val askSw = askRow.findViewById<Switch>(R.id.rowSwitch)
        askSw.isChecked = SettingsPrefs.getBool(this, SettingsPrefs.K_ASK_DL, true)
        askSw.setOnCheckedChangeListener { _, b ->
            SettingsPrefs.setBool(this, SettingsPrefs.K_ASK_DL, b)
        }
        c.addView(askRow)

        val title = android.widget.TextView(this).apply {
            text = "DOWNLOAD LOCATION"
            setTextColor(0xFF8A96A8.toInt())
            textSize = 12f
            setPadding(16, 32, 16, 8)
        }
        c.addView(title)

        val opts = listOf("Data Vault", "Public Storage", "External Downloader")
        val current = SettingsPrefs.getInt(this, SettingsPrefs.K_DL_LOCATION, 1)
        for ((i, name) in opts.withIndex()) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundColor(0xFF171B21.toInt())
                setPadding(32, 32, 32, 32)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 2 }
                isClickable = true
                setOnClickListener {
                    SettingsPrefs.setInt(this@DownloadSettingsActivity, SettingsPrefs.K_DL_LOCATION, i)
                    recreate()
                }
            }
            val radio = android.widget.RadioButton(this).apply {
                isChecked = (i == current)
                isClickable = false
            }
            val label = android.widget.TextView(this).apply {
                text = name
                setTextColor(0xFFE6E9EE.toInt())
                textSize = 15f
                setPadding(24, 0, 0, 0)
            }
            row.addView(radio)
            row.addView(label)
            c.addView(row)
        }
    }
}
