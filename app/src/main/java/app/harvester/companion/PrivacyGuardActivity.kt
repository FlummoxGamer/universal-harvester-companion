package app.harvester.companion

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class PrivacyGuardActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "Privacy Guard\n(full screen in next batch)"
            setTextColor(0xFFE6EDF5.toInt())
            textSize = 16f
            setPadding(60, 120, 60, 60)
            setBackgroundColor(0xFF0B0F14.toInt())
        }
        setContentView(tv)
    }
}
