package app.harvester.companion

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val textView = TextView(this).apply {
            text = "Diagnostic Screen: If you see this, MainActivity works."
            textSize = 24f
            setPadding(50, 50, 50, 50)
        }
        setContentView(textView)
    }
}
