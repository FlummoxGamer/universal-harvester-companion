package app.harvester.companion

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class ExtensionsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(android.widget.TextView(this).apply {
            text = "Extensions manager — coming in the next build."
            setTextColor(0xFFE6EDF5.toInt())
            setBackgroundColor(0xFF0A0D14.toInt())
            setPadding(40, 80, 40, 40)
        })
    }
}
