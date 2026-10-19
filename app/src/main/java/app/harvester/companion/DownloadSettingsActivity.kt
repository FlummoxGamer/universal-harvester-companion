package app.harvester.companion

import android.content.Intent
import android.os.Bundle
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.view.View

class DownloadSettingsActivity : AppCompatActivity() {

    private lateinit var radioInternal: RadioButton
    private lateinit var radioExternal: RadioButton
    private lateinit var tvInternalPath: TextView

    private val selectFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            SettingsPrefs.setString(this, "custom_download_uri", uri.toString())
            updateUi()
            Toast.makeText(this, "Location updated", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download_settings)

        findViewById<View>(R.id.dlSettingsBack).setOnClickListener { finish() }

        radioInternal = findViewById(R.id.radioInternal)
        radioExternal = findViewById(R.id.radioExternal)
        tvInternalPath = findViewById(R.id.tvInternalPath)

        val swAsk = findViewById<Switch>(R.id.swAskBeforeDl)
        swAsk.isChecked = SettingsPrefs.getBool(this, SettingsPrefs.K_ASK_DL, true)
        swAsk.setOnCheckedChangeListener { _, b ->
            SettingsPrefs.setBool(this, SettingsPrefs.K_ASK_DL, b)
        }

        findViewById<View>(R.id.rowInternalStorage).setOnClickListener {
            SettingsPrefs.setInt(this, SettingsPrefs.K_DL_LOCATION, 1)
            updateUi()
        }

        tvInternalPath.setOnClickListener {
            selectFolderLauncher.launch(null)
        }

        findViewById<View>(R.id.rowExternalDownloader).setOnClickListener {
            SettingsPrefs.setInt(this, SettingsPrefs.K_DL_LOCATION, 2)
            updateUi()
        }

        findViewById<View>(R.id.rowExternalDownloader).setOnLongClickListener {
            showResetDialog()
            true
        }

        updateUi()
    }

    private fun updateUi() {
        val mode = SettingsPrefs.getInt(this, SettingsPrefs.K_DL_LOCATION, 1)
        radioInternal.isChecked = mode == 1
        radioExternal.isChecked = mode == 2

        val customUri = SettingsPrefs.getString(this, "custom_download_uri", "")
        if (customUri.isNotBlank()) {
            tvInternalPath.text = customUri.replace("content://com.android.externalstorage.documents/tree/primary%3A", "/storage/emulated/0/").replace("%2F", "/")
        } else {
            tvInternalPath.text = "/storage/emulated/0/Download"
        }
    }

    private fun showResetDialog() {
        AlertDialog.Builder(this)
            .setTitle("Reset to default?")
            .setMessage("This will restore the download location to Internal Storage.")
            .setPositiveButton("Reset") { _, _ ->
                SettingsPrefs.setInt(this, SettingsPrefs.K_DL_LOCATION, 1)
                SettingsPrefs.setString(this, "custom_download_uri", "")
                updateUi()
                Toast.makeText(this, "Reset to default", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
