package app.harvester.companion

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
    private var pendingName: String? = null

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
            onToggle = { name, enabled ->
                UserscriptStore.setEnabled(this, name, enabled)
            },
            onDelete = { name -> confirmDelete(name) }
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
        adapter.submit(scripts.map {
            ScriptAdapter.Row(
                it.name,
                it.displayName,
                it.description,
                it.version,
                it.runAt,
                UserscriptStore.isEnabled(this, it.name)
            )
        })
        emptyView.visibility = if (scripts.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (scripts.isEmpty()) View.GONE else View.VISIBLE
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
        private val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<ScriptAdapter.VH>() {

        data class Row(
            val name: String,
            val displayName: String,
            val description: String,
            val version: String,
            val runAt: String,
            var enabled: Boolean
        )

        private val rows = mutableListOf<Row>()

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.scriptTitle)
            val sub: TextView = v.findViewById(R.id.scriptSub)
            val sw: Switch = v.findViewById(R.id.scriptSwitch)
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
            val meta = buildString {
                if (r.version.isNotBlank()) append("v").append(r.version).append(" · ")
                append(r.runAt)
                if (r.description.isNotBlank()) append("\n").append(r.description)
            }
            h.sub.text = meta
            h.sw.setOnCheckedChangeListener(null)
            h.sw.isChecked = r.enabled
            h.sw.setOnCheckedChangeListener { _, checked ->
                r.enabled = checked
                onToggle(r.name, checked)
            }
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
