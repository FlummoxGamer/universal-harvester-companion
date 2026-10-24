package app.harvester.companion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ScriptsActivity : AppCompatActivity() {

    private lateinit var adapter: ScriptAdapter
    private lateinit var emptyView: TextView
    private lateinit var list: RecyclerView

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            if (UserscriptManager.installFromUri(this, it)) {
                Toast.makeText(this, "Installed", Toast.LENGTH_SHORT).show()
                reload()
            } else {
                Toast.makeText(this, "Install failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scripts)

        list = findViewById(R.id.scriptList)
        emptyView = findViewById(R.id.emptyLabel)
        adapter = ScriptAdapter(
            onToggle = { name, enabled -> UserscriptStore.setEnabled(this, name, enabled) },
            onDelete = { name -> confirmDelete(name) },
            onEdit = { name -> openEditor(name) },
            onInfo = { script -> showInfo(script) },
            onOpenHomepage = { url -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {} }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<View>(R.id.btnInstall).setOnClickListener { showInstallOptions() }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val scripts = UserscriptManager.loadScripts(this)
        adapter.submit(scripts.map { s ->
            ScriptAdapter.Row(
                name = s.name,
                displayName = s.displayName,
                description = s.description,
                version = s.version,
                author = s.author,
                homepage = s.homepage,
                runAt = s.runAt,
                patterns = s.patterns,
                grants = s.grants,
                connects = s.connects,
                requires = s.requires,
                usesSystemjs = s.usesSystemjs,
                fileSize = s.fileSize,
                enabled = UserscriptStore.isEnabled(this, s.name),
                iconUrl = s.iconUrl
            )
        })
        emptyView.visibility = if (scripts.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (scripts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openEditor(name: String) {
        val intent = Intent(this, ScriptEditorActivity::class.java)
        intent.putExtra("name", name)
        startActivity(intent)
    }

    private fun showInfo(row: ScriptAdapter.Row) {
        val sb = StringBuilder()
        if (row.version.isNotBlank()) sb.append("Version: ").append(row.version).append("\n")
        if (row.author.isNotBlank()) sb.append("Author: ").append(row.author).append("\n")
        sb.append("Run at: ").append(row.runAt).append("\n")
        sb.append("Size: ").append(formatSize(row.fileSize)).append("\n")
        sb.append("SystemJS: ").append(if (row.usesSystemjs) "yes" else "no").append("\n")
        if (row.patterns.isNotEmpty()) {
            sb.append("\nMatches:\n")
            row.patterns.take(10).forEach { sb.append("  ").append(it).append("\n") }
            if (row.patterns.size > 10) sb.append("  …").append(row.patterns.size - 10).append(" more\n")
        }
        if (row.grants.isNotEmpty()) {
            sb.append("\nGrants:\n")
            row.grants.take(15).forEach { sb.append("  ").append(it).append("\n") }
            if (row.grants.size > 15) sb.append("  …").append(row.grants.size - 15).append(" more\n")
        }
        if (row.connects.isNotEmpty()) {
            sb.append("\nConnect:\n")
            row.connects.take(10).forEach { sb.append("  ").append(it).append("\n") }
        }
        if (row.requires.isNotEmpty()) {
            sb.append("\nRequires: ").append(row.requires.size).append(" external script(s)\n")
        }
        if (row.description.isNotBlank()) {
            sb.append("\n").append(row.description)
        }
        AlertDialog.Builder(this)
            .setTitle(row.displayName.ifBlank { row.name })
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun formatSize(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        else -> "%.1f MB".format(b / (1024.0 * 1024.0))
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete $name?")
            .setPositiveButton("Delete") { _, _ ->
                UserscriptManager.uninstall(this, name)
                reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showInstallOptions() {
        val opts = arrayOf("From URL", "From File", "Write / Paste")
        AlertDialog.Builder(this)
            .setTitle("Install userscript")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> showUrlDialog()
                    1 -> filePicker.launch(arrayOf("*/*"))
                    2 -> showTextDialog()
                }
            }
            .show()
    }

    private fun showUrlDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com/script.user.js"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Install from URL")
            .setView(input)
            .setPositiveButton("Install") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) return@setPositiveButton
                Toast.makeText(this, "Downloading…", Toast.LENGTH_SHORT).show()
                UserscriptManager.installFromUrl(this, url) { ok ->
                    runOnUiThread {
                        Toast.makeText(this, if (ok) "Installed" else "Failed", Toast.LENGTH_SHORT).show()
                        reload()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTextDialog() {
        val input = EditText(this).apply {
            hint = "// ==UserScript==\n// @name ...\n// @match ...\n// ==/UserScript==\n\n(your script here)"
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            minLines = 12
            maxLines = 20
            setHorizontallyScrolling(false)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        AlertDialog.Builder(this)
            .setTitle("Paste or write userscript")
            .setView(input)
            .setPositiveButton("Install") { _, _ ->
                val body = input.text.toString()
                if (body.isBlank()) return@setPositiveButton
                if (UserscriptManager.installFromText(this, body)) {
                    Toast.makeText(this, "Installed", Toast.LENGTH_SHORT).show()
                    reload()
                } else {
                    Toast.makeText(this, "Install failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    class ScriptAdapter(
        private val onToggle: (String, Boolean) -> Unit,
        private val onDelete: (String) -> Unit,
        private val onEdit: (String) -> Unit,
        private val onInfo: (Row) -> Unit,
        private val onOpenHomepage: (String) -> Unit
    ) : RecyclerView.Adapter<ScriptAdapter.VH>() {

        data class Row(
            val name: String,
            val displayName: String,
            val description: String,
            val version: String,
            val author: String,
            val homepage: String,
            val runAt: String,
            val patterns: List<String>,
            val grants: List<String>,
            val connects: List<String>,
            val requires: List<String>,
            val usesSystemjs: Boolean,
            val fileSize: Long,
            var enabled: Boolean,
            val iconUrl: String
        )

        private val rows = mutableListOf<Row>()

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.scriptTitle)
            val sub: TextView = v.findViewById(R.id.scriptSub)
            val patterns: TextView = v.findViewById(R.id.scriptPatterns)
            val sw: Switch = v.findViewById(R.id.scriptSwitch)
            val edit: ImageView = v.findViewById(R.id.scriptEdit)
            val info: ImageView = v.findViewById(R.id.scriptInfo)
            val home: ImageView = v.findViewById(R.id.scriptHome)
            val del: ImageView = v.findViewById(R.id.scriptDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_script, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            h.title.text = r.displayName.ifBlank { r.name }
            val meta = StringBuilder()
            if (r.version.isNotBlank()) meta.append("v").append(r.version)
            if (r.author.isNotBlank()) {
                if (meta.isNotEmpty()) meta.append(" · ")
                meta.append("by ").append(r.author)
            }
            if (meta.isNotEmpty()) meta.append(" · ")
            meta.append(r.runAt)
            if (r.description.isNotBlank()) meta.append("\n").append(r.description)
            h.sub.text = meta.toString()

            if (r.iconUrl.isNotBlank()) {
    Thread {
        try {
            val bmp = java.net.URL(r.iconUrl).openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
            if (bmp != null) {
                h.title.post { h.title.setCompoundDrawablesWithIntrinsicBounds(null, null, android.graphics.drawable.BitmapDrawable(h.title.resources, bmp), null) }
            }
        } catch (_: Exception) {}
    }.start()
}
val pats = if (r.patterns.size <= 2) r.patterns.joinToString(" ") 
            else r.patterns.take(2).joinToString(" ") + " +${r.patterns.size - 2}"
            h.patterns.text = pats

            h.sw.setOnCheckedChangeListener(null)
            h.sw.isChecked = r.enabled
            h.sw.setOnCheckedChangeListener { _, checked ->
                r.enabled = checked
                onToggle(r.name, checked)
            }

            h.edit.setOnClickListener { onEdit(r.name) }
            h.info.setOnClickListener { onInfo(r) }
            h.home.visibility = if (r.homepage.isNotBlank()) View.VISIBLE else View.GONE
            h.home.setOnClickListener { if (r.homepage.isNotBlank()) onOpenHomepage(r.homepage) }
            h.del.setOnClickListener { onDelete(r.name) }
        }

        override fun getItemCount() = rows.size

        fun submit(newList: List<Row>) {
            rows.clear()
            rows.addAll(newList)
            notifyDataSetChanged()
        }
    }
}
