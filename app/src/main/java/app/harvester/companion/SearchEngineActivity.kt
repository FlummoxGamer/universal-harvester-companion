package app.harvester.companion

import android.os.Bundle
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
        findViewById<android.view.View>(R.id.searchEngineBack).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.searchEngineAdd).setOnClickListener {
            showAddDialog()
        }
        rebuild()
    }

    private fun rebuild() {
        container.removeAllViews()
        val current = SearchEnginePrefs.getEngine(this)
        val builtIns = listOf(
            Triple(SearchEnginePrefs.ENGINE_BRAVE, "Brave", "search.brave.com"),
            Triple(SearchEnginePrefs.ENGINE_GOOGLE, "Google", "google.com"),
            Triple(SearchEnginePrefs.ENGINE_BING, "Bing", "bing.com"),
            Triple(SearchEnginePrefs.ENGINE_YAHOO, "Yahoo", "yahoo.com"),
            Triple(SearchEnginePrefs.ENGINE_DUCKDUCKGO, "DuckDuckGo", "duckduckgo.com"),
            Triple(SearchEnginePrefs.ENGINE_YANDEX, "Yandex", "yandex.com")
        )
        for ((id, name, host) in builtIns) addRow(id, name, host, current == id, false)
        for (custom in SearchEnginePrefs.listCustom(this)) {
            addRow(custom.id, custom.name, custom.host, current == custom.id, true)
        }
    }

    private fun addRow(id: String, name: String, host: String, selected: Boolean, custom: Boolean) {
        val row = layoutInflater.inflate(R.layout.item_search_engine, container, false)
        row.findViewById<android.widget.TextView>(R.id.seName).text = name
        row.findViewById<android.widget.TextView>(R.id.seHost).text = host
        row.findViewById<android.view.View>(R.id.seCheck).visibility =
            if (selected) android.view.View.VISIBLE else android.view.View.INVISIBLE
        row.setOnClickListener {
            SearchEnginePrefs.setEngine(this, id)
            rebuild()
        }
        if (custom) {
            row.setOnLongClickListener {
                confirmDeleteCustom(id, name)
                true
            }
        }
        container.addView(row)
    }

    private fun confirmDeleteCustom(id: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete $name?")
            .setPositiveButton("Delete") { _, _ ->
                SearchEnginePrefs.deleteCustom(this, id)
                if (SearchEnginePrefs.getEngine(this) == id) {
                    SearchEnginePrefs.setEngine(this, SearchEnginePrefs.ENGINE_BRAVE)
                }
                rebuild()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddDialog() {
        val nameInput = EditText(this).apply { hint = "Name"; setSingleLine() }
        val urlInput = EditText(this).apply {
            hint = "https://example.com/search?q=%s"
            setSingleLine()
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 16, 40, 16)
            addView(nameInput); addView(urlInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Add custom search engine")
            .setView(wrap)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                when {
                    name.isBlank() -> toast("Name required")
                    !url.startsWith("http://") && !url.startsWith("https://") ->
                        toast("URL must start with http:// or https://")
                    !url.contains("%s") -> toast("URL must contain %s")
                    !url.contains("/search") && !url.contains("?") && !url.contains("/?") ->
                        toast("Not a valid search URL")
                    else -> {
                        SearchEnginePrefs.addCustom(this, name, url)
                        rebuild()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
