package app.harvester.companion

import android.os.Bundle
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class AppearanceActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_appearance)

        findViewById<android.view.View>(R.id.appearanceBack).setOnClickListener { finish() }

        val rg = findViewById<RadioGroup>(R.id.appearanceThemeGroup)
        rg.check(when (ThemePrefs.getMode(this)) {
            ThemePrefs.MODE_LIGHT -> R.id.appearanceThemeLight
            ThemePrefs.MODE_DARK -> R.id.appearanceThemeDark
            else -> R.id.appearanceThemeSystem
        })
        rg.setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                R.id.appearanceThemeLight -> ThemePrefs.MODE_LIGHT
                R.id.appearanceThemeDark -> ThemePrefs.MODE_DARK
                else -> ThemePrefs.MODE_SYSTEM
            }
            ThemePrefs.setMode(this, mode)
        }

        val enhance = findViewById<Switch>(R.id.appearanceEnhanceDark)
        enhance.isChecked = SettingsPrefs.getBool(this, SettingsPrefs.K_ENHANCE_DARK, false)
        enhance.setOnCheckedChangeListener { _, b ->
            SettingsPrefs.setBool(this, SettingsPrefs.K_ENHANCE_DARK, b)
        }

        findViewById<android.view.View>(R.id.appearanceWallpaper).setOnClickListener {
            Toast.makeText(this, "Wallpaper coming soon", Toast.LENGTH_SHORT).show()
        }
        findViewById<android.view.View>(R.id.appearanceAddressBar).setOnClickListener {
            startActivity(android.content.Intent(this, CustomizeAddressBarActivity::class.java))
        }
        findViewById<android.view.View>(R.id.appearanceMenu).setOnClickListener {
            Toast.makeText(this, "Coming soon", Toast.LENGTH_SHORT).show()
        }
        findViewById<android.view.View>(R.id.appearanceTabs).setOnClickListener {
            Toast.makeText(this, "Coming soon", Toast.LENGTH_SHORT).show()
        }
        findViewById<android.view.View>(R.id.appearanceLang).setOnClickListener {
            Toast.makeText(this, "Coming soon", Toast.LENGTH_SHORT).show()
        }
    }
}
