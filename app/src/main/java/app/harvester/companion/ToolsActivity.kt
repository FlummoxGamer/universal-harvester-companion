package app.harvester.companion

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ToolsActivity : AppCompatActivity() {

    private lateinit var statusPill: View
    private lateinit var statusLabel: TextView
    private lateinit var sessionLabel: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnSave: Button
    private lateinit var adapter: CategoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tools)
        SessionRecorder.init(this)

        statusPill = findViewById(R.id.statusPill)
        statusLabel = findViewById(R.id.statusLabel)
        sessionLabel = findViewById(R.id.sessionLabel)
        btnToggle = findViewById(R.id.btnToggle)
        btnSave = findViewById(R.id.btnSave)

        adapter = CategoryAdapter(
            items = SessionRecorder.ALL_CATEGORIES.map { key ->
                CategoryAdapter.Item(key, prettyLabel(key), SessionRecorder.isEnabled(key))
            }.toMutableList(),
            onToggle = { key, enabled -> SessionRecorder.setEnabled(key, enabled) }
        )
        findViewById<RecyclerView>(R.id.categoryList).apply {
            layoutManager = LinearLayoutManager(this@ToolsActivity)
            adapter = this@ToolsActivity.adapter
        }

        btnToggle.setOnClickListener {
            if (SessionRecorder.isActive()) {
                SessionRecorder.pause()
            } else {
                SessionRecorder.start(this)
            }
            refreshState()
        }

        btnSave.setOnClickListener {
            val file = SessionRecorder.save(this)
            if (file != null) {
                Toast.makeText(this, "Saved $file", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.btnViewSessions).setOnClickListener {
            Toast.makeText(this, "Coming in a later batch", Toast.LENGTH_SHORT).show()
        }

        refreshState()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        val active = SessionRecorder.isActive()
        statusPill.setBackgroundColor(
            if (active) 0xFF4ADE80.toInt() else 0xFF8A96A8.toInt()
        )
        statusLabel.text = if (active) "Capturing" else "Idle"
        sessionLabel.text = "Session ${UHLog.sessionId} · ${SessionRecorder.count()} events"
        btnToggle.text = if (active) "Stop Session" else "Start Session"
    }

    private fun prettyLabel(key: String): String = when (key) {
        "header" -> "Request Headers"
        "wasm_blob" -> "WASM Blob"
        "wasm_memory" -> "WASM Memory"
        "crypto" -> "crypto.subtle"
        "atob" -> "atob / TextDecoder"
        "textdecoder" -> "TextDecoder"
        "worker_fetch" -> "Worker Fetches"
        "sw_fetch" -> "Service Worker Fetches"
        "wasm_call" -> "WASM Call Trace"
        else -> key
    }

    class CategoryAdapter(
        private val items: MutableList<Item>,
        private val onToggle: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.VH>() {

        data class Item(val key: String, val label: String, var enabled: Boolean)

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val label: TextView = v.findViewById(R.id.rowLabel)
            val sw: android.widget.Switch = v.findViewById(R.id.rowSwitch)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_tool_category, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val item = items[position]
            h.label.text = item.label
            h.sw.setOnCheckedChangeListener(null)
            h.sw.isChecked = item.enabled
            h.sw.setOnCheckedChangeListener { _, checked ->
                item.enabled = checked
                onToggle(item.key, checked)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
