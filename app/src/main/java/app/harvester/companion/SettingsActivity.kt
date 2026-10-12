package app.harvester.companion

import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.harvester.companion.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.versionLabel.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        binding.btnLogs.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }
        binding.btnAppUpdate.setOnClickListener {
            UpdateChecker.checkAndPrompt(this)
        }
        binding.btnClearState.setOnClickListener { confirmClear() }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear all state?")
            .setSubtitle(null)
            .setMessage("Cookies, cached data, page storage, and installed userscripts will be wiped.")
            .setPositiveButton("Clear") { _, _ ->
                try {
                    getSharedPreferences("uh_gm", MODE_PRIVATE).edit().clear().apply()
                    java.io.File(filesDir, "scripts").deleteRecursively()
                } catch (_: Exception) {}
                Toast.makeText(this, "State cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
