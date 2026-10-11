package app.harvester.companion

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

class ExtensionsActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: ExtAdapter

    private val controller: WebExtensionController
        get() = GeckoEngine.runtime().webExtensionController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UHLog.section("EXT")
        setContentView(buildRoot())
        refresh()
    }

    // ─── Layout ───────────────────────────────────────────────────────────────

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0A0D14.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Extensions"
            setTextColor(0xFFE6EDF5.toInt())
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "Firefox-format add-ons only."
            setTextColor(0xFF8296AD.toInt())
            textSize = 12f
            setPadding(0, dp(4), 0, dp(14))
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val btnInstall = Button(this).apply {
            text = "Install from URL"
            isAllCaps = false
            setTextColor(0xFF0A0D14.toInt())
            setBackgroundColor(0xFF38BDF8.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                rightMargin = dp(4)
            }
            setOnClickListener { promptInstallUrl() }
        }
        val btnRefresh = Button(this).apply {
            text = "Refresh"
            isAllCaps = false
            setTextColor(0xFF7DD3FC.toInt())
            setBackgroundColor(0xFF141B28.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                leftMargin = dp(4)
            }
            setOnClickListener { refresh() }
        }
        actions.addView(btnInstall)
        actions.addView(btnRefresh)
        root.addView(actions)

        empty = TextView(this).apply {
            text = "No extensions installed."
            setTextColor(0xFF8296AD.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(40), 0, 0)
        }
        root.addView(empty)

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ExtensionsActivity)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(12) }
        }
        adapter = ExtAdapter(
            onToggle = { ext, enable -> toggleExtension(ext, enable) },
            onRemove = { ext -> confirmUninstall(ext) }
        )
        list.adapter = adapter
        root.addView(list)

        return root
    }

    // ─── Refresh list ─────────────────────────────────────────────────────────

    private fun refresh() {
        controller.list().accept({ exts ->
            runOnUiThread {
                adapter.submit(exts.orEmpty())
                empty.visibility = if (exts.isNullOrEmpty()) View.VISIBLE else View.GONE
                UHLog.d("ext", "listed ${exts?.size ?: 0} extensions")
            }
        }, { err ->
            err?.let { UHLog.throwable("ext", "list failed", it) }
            runOnUiThread {
                Toast.makeText(this, "Failed to list extensions", Toast.LENGTH_SHORT).show()
            }
        })
    }

    // ─── Install from URL ─────────────────────────────────────────────────────

    private fun promptInstallUrl() {
        val input = EditText(this).apply {
            setHint("https://addons.mozilla.org/... or .xpi URL")
            setHintTextColor(0xFF8296AD.toInt())
            setTextColor(0xFFE6EDF5.toInt())
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Install extension")
            .setView(input)
            .setPositiveButton("Install") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) return@setPositiveButton
                UHLog.d("ext", "install requested $url")
                controller.install(url).accept({ ext ->
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Installed ${ext?.metaData?.name ?: "extension"}",
                            Toast.LENGTH_SHORT
                        ).show()
                        refresh()
                    }
                }, { err ->
                    err?.let { UHLog.throwable("ext", "install failed", it) }
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("Install failed")
                            .setMessage(err?.message ?: "Unknown error")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Toggle ───────────────────────────────────────────────────────────────

    private fun toggleExtension(ext: WebExtension, enable: Boolean) {
        val op = if (enable) {
            controller.enable(ext, WebExtensionController.EnableSource.USER)
        } else {
            controller.disable(ext, WebExtensionController.EnableSource.USER)
        }
        op.accept({
            UHLog.d("ext", "${if (enable) "enabled" else "disabled"} ${ext.metaData.name}")
            runOnUiThread { refresh() }
        }, { err ->
            err?.let { UHLog.throwable("ext", "toggle failed", it) }
            runOnUiThread {
                Toast.makeText(this, "Failed: ${err?.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    // ─── Uninstall ────────────────────────────────────────────────────────────

    private fun confirmUninstall(ext: WebExtension) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${ext.metaData.name}?")
            .setMessage("The extension and its stored data will be deleted.")
            .setPositiveButton("Remove") { _, _ ->
                controller.uninstall(ext).accept({
                    UHLog.d("ext", "uninstalled ${ext.metaData.name}")
                    runOnUiThread { refresh() }
                }, { err ->
                    err?.let { UHLog.throwable("ext", "uninstall failed", it) }
                    runOnUiThread {
                        Toast.makeText(this, "Failed: ${err?.message}", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ─── Adapter ──────────────────────────────────────────────────────────────

    private class ExtAdapter(
        private val onToggle: (WebExtension, Boolean) -> Unit,
        private val onRemove: (WebExtension) -> Unit
    ) : RecyclerView.Adapter<ExtAdapter.VH>() {

        private var items: List<WebExtension> = emptyList()

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.extName)
            val meta: TextView = v.findViewById(R.id.extMeta)
            val toggle: ImageView = v.findViewById(R.id.extToggle)
            val remove: ImageView = v.findViewById(R.id.extRemove)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_extension, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            val m = e.metaData
            h.name.text = m.name
            h.meta.text = "v${m.version} · ${if (m.enabled) "enabled" else "disabled"}"
            h.toggle.alpha = if (m.enabled) 1f else 0.4f
            h.toggle.setImageResource(
                if (m.enabled) R.drawable.ic_ext_on else R.drawable.ic_ext_off
            )
            h.toggle.setOnClickListener { onToggle(e, !m.enabled) }
            h.remove.setOnClickListener { onRemove(e) }
        }

        override fun getItemCount(): Int = items.size

        fun submit(newItems: List<WebExtension>) {
            items = newItems
            notifyDataSetChanged()
        }
    }
}
