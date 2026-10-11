package app.harvester.companion

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.harvester.companion.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.scriptUrl.setText(AppPrefs.scriptUrl(this))
        binding.autoUpdate.isChecked = AppPrefs.autoUpdate(this)
        refreshVersionLabel()

        binding.btnCheckNow.setOnClickListener { checkScriptNow() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnClearState.setOnClickListener { confirmClear() }

        setupSelfUpdateButton()
    }

    private fun refreshVersionLabel() {
        val v = ScriptRepository(this).version()
        binding.versionLabel.text = if (v.isNullOrBlank())
            getString(R.string.settings_script_missing)
        else getString(R.string.settings_last_version, v)
    }

    private fun save() {
        val url = binding.scriptUrl.text.toString().trim()
        AppPrefs.setScriptUrl(this, url)
        AppPrefs.setAutoUpdate(this, binding.autoUpdate.isChecked)
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun checkScriptNow() {
        val url = binding.scriptUrl.text.toString().trim()
        if (url.isBlank()) {
            Toast.makeText(this, "Enter URL first", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Downloading…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = ScriptRepository(this@SettingsActivity).download(url)
            result.onSuccess { v ->
                AppPrefs.setScriptUrl(this@SettingsActivity, url)
                AppPrefs.setLastCheck(this@SettingsActivity, System.currentTimeMillis())
                AppPrefs.setLastVersion(this@SettingsActivity, v)
                refreshVersionLabel()
                Toast.makeText(this@SettingsActivity, getString(R.string.toast_update_ok, v), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@SettingsActivity, getString(R.string.toast_update_fail), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear all state?")
            .setMessage("Cookies, cached script, GM values, page storage will be wiped.")
            .setPositiveButton("Clear") { _, _ ->
                getSharedPreferences("uh_gm", MODE_PRIVATE).edit().clear().apply()
                java.io.File(filesDir, "scripts/current.js").delete()
                java.io.File(filesDir, "scripts/meta.json").delete()
                refreshVersionLabel()
                Toast.makeText(this, R.string.toast_state_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupSelfUpdateButton() {
        val container = binding.root as android.widget.LinearLayout
        val btn = android.widget.Button(this).apply {
            text = "Check for app update"
            setBackgroundColor(0xFF38BDF8.toInt())
            setTextColor(0xFF0A0D14.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 32 }
            setOnClickListener { UpdateChecker.checkAndPrompt(this@SettingsActivity) }
        }
        container.addView(btn)

        val info = android.widget.TextView(this).apply {
            text = "Current: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            setTextColor(0xFF8296AD.toInt())
            textSize = 11f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }
        container.addView(info)
    }
}
