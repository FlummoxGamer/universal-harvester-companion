package app.harvester.companion

import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class ManageDataActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_simple)

        findViewById<TextView>(R.id.simpleTitle).text = "Manage Personal Data"
        findViewById<View>(R.id.simpleBack).setOnClickListener { finish() }

        val c = findViewById<LinearLayout>(R.id.simpleContent)

        addAction(c, "Clear cookies") {
            try {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                toast("Cookies cleared")
            } catch (_: Exception) { toast("Failed") }
        }
        addAction(c, "Clear cache") {
            try { WebStorage.getInstance().deleteAllData(); toast("Cache cleared") }
            catch (_: Exception) { toast("Failed") }
        }
        addAction(c, "Clear userscript storage") {
            try {
                getSharedPreferences("uh_gm", MODE_PRIVATE).edit().clear().apply()
                toast("Script storage cleared")
            } catch (_: Exception) { toast("Failed") }
        }
        addAction(c, "Clear all captured session files") {
            confirmClearSessions()
        }
    }

    private fun addAction(parent: LinearLayout, label: String, action: () -> Unit) {
        val btn = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(0xFFE6E9EE.toInt())
            setBackgroundColor(0xFF171B21.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4 }
            setOnClickListener { action() }
        }
        parent.addView(btn)
    }

    private fun confirmClearSessions() {
        AlertDialog.Builder(this)
            .setTitle("Clear captured sessions?")
            .setMessage("Removes all session folders from app storage.")
            .setPositiveButton("Clear") { _, _ ->
                try {
                    val dir = File(filesDir, "sessions")
                    dir.deleteRecursively()
                    toast("Sessions cleared")
                } catch (_: Exception) { toast("Failed") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
