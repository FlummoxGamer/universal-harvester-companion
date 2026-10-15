package app.harvester.companion

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SearchEngineActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search_engine)
        container = findViewById(R.id.searchEngineList)
        findViewById<View>(R.id.searchEngineBack).setOnClickListener { finish() }
        findViewById<View>(R.id.searchEngineAdd).setOnClickListener { showAddDialog() }
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun rebuild() {
        container.removeAllViews()
        val current = SearchEnginePrefs.getEngine(this)
        val entries = SearchEnginePrefs.all(this)
        for (entry in entries) {
            addRow(entry, entry.id == current)
        }
    }

    private fun addRow(entry: SearchEnginePrefs.Entry, selected: Boolean) {
        val row = layoutInflater.inflate(R.layout.item_search_engine, container, false)
        row.findViewById<android.widget.TextView>(R.id.seName).text = entry.name
        row.findViewById<android.widget.TextView>(R.id.seHost).text = entry.host
        row.findViewById<View>(R.id.seCheck).visibility =
            if (selected) View.VISIBLE else View.INVISIBLE
        row.setOnClickListener {
            SearchEnginePrefs.setEngine(this, entry.id)
            rebuild()
        }
        if (entry.isCustom) {
            row.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Delete ${entry.name}?")
                    .setPositiveButton("Delete") { _, _ ->
                        SearchEnginePrefs.deleteCustom(this, entry.id)
                        if (SearchEnginePrefs.getEngine(this) == entry.id) {
                            SearchEnginePrefs.setEngine(this, SearchEnginePrefs.ENGINE_BRAVE)
                        }
                        rebuild()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }
        container.addView(row)
    }

    private fun showAddDialog() {
        val nameInput = EditText(this).apply {
            hint = "Name (optional)"
            setSingleLine()
        }
        val urlInput = EditText(this).apply {
            hint = "https://web.archive.org"
            setSingleLine()
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 16, 40, 16)
            addView(nameInput)
            addView(urlInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Add search engine")
            .setMessage("Enter the site's base URL. We'll handle the query format automatically.")
            .setView(wrap)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text.toString()
                val url = urlInput.text.toString()
                if (url.isBlank()) {
                    toast("URL is required")
                    return@setPositiveButton
                }
                if (SearchEnginePrefs.addCustom(this, name, url)) {
                    rebuild()
                    toast("Added")
                } else {
                    toast("Invalid URL. Use https://...")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
