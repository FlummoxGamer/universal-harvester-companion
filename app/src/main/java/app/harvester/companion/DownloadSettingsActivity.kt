package app.harvester.companion

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class DownloadSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_simple)

        findViewById<TextView>(R.id.simpleTitle).text = "Download Settings"
        findViewById<View>(R.id.simpleBack).setOnClickListener { finish() }

        val c = findViewById<LinearLayout>(R.id.simpleContent)

        val askRow = layoutInflater.inflate(R.layout.item_tool_category, c, false)
        askRow.findViewById<TextView>(R.id.rowLabel).text = "Ask Before Downloading"
        val askSw = askRow.findViewById<Switch>(R.id.rowSwitch)
        askSw.isChecked = SettingsPrefs.getBool(this, SettingsPrefs.K_ASK_DL, true)
        askSw.setOnCheckedChangeListener { _, b ->
            SettingsPrefs.setBool(this, SettingsPrefs.K_ASK_DL, b)
        }
        c.addView(askRow)

        val title = TextView(this).apply {
            text = "DOWNLOAD LOCATION"
            setTextColor(ContextCompat.getColor(this@DownloadSettingsActivity, R.color.subtext))
            textSize = 12f
            setPadding(16, 32, 16, 8)
        }
        c.addView(title)

        val opts = listOf("Data Vault", "Public Storage", "External Downloader")
        val current = SettingsPrefs.getInt(this, SettingsPrefs.K_DL_LOCATION, 1)
        for ((i, name) in opts.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundColor(ContextCompat.getColor(
                    this@DownloadSettingsActivity, R.color.surface))
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
            val radio = RadioButton(this).apply {
                isChecked = (i == current)
                isClickable = false
                buttonTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@DownloadSettingsActivity, R.color.accent)
                )
            }
            val label = TextView(this).apply {
                text = name
                setTextColor(ContextCompat.getColor(
                    this@DownloadSettingsActivity, R.color.text))
                textSize = 15f
                setPadding(24, 0, 0, 0)
            }
            row.addView(radio)
            row.addView(label)
            c.addView(row)
        }
    }
}
