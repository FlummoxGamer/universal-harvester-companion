package app.harvester.companion

import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity

class CustomizeAddressBarActivity : AppCompatActivity() {

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_customize_address_bar)

    findViewById<View>(R.id.addrBarBack).setOnClickListener { finish() }

    val topRadio = findViewById<RadioButton>(R.id.addrBarTop)
    val bottomRadio = findViewById<RadioButton>(R.id.addrBarBottom)

    when (SettingsPrefs.getInt(this, SettingsPrefs.K_ADDRESS_BAR_POSITION, 1)) {
        0 -> topRadio.isChecked = true
        else -> bottomRadio.isChecked = true
    }

    topRadio.setOnClickListener {
        SettingsPrefs.setInt(this, SettingsPrefs.K_ADDRESS_BAR_POSITION, 0)
    }
    bottomRadio.setOnClickListener {
        SettingsPrefs.setInt(this, SettingsPrefs.K_ADDRESS_BAR_POSITION, 1)
    }

    val autoHide = findViewById<android.widget.Switch>(R.id.addrBarAutoHide)
    autoHide.isChecked = SettingsPrefs.getBool(this, SettingsPrefs.K_AUTO_HIDE_BAR, false)
    autoHide.setOnCheckedChangeListener { _, checked ->
        SettingsPrefs.setBool(this, SettingsPrefs.K_AUTO_HIDE_BAR, checked)
    }

    val swipeFs = findViewById<android.widget.Switch>(R.id.addrBarSwipeFullscreen)
    swipeFs.isChecked = SettingsPrefs.getBool(this, SettingsPrefs.K_SWIPE_FULLSCREEN, false)
    swipeFs.setOnCheckedChangeListener { _, checked ->
        SettingsPrefs.setBool(this, SettingsPrefs.K_SWIPE_FULLSCREEN, checked)
    }
}
}
