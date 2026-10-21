package app.harvester.companion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.mozilla.geckoview.WebExtension
import java.net.URL

class ExtensionsActivity : AppCompatActivity() {

    private lateinit var adapter: ExtAdapter
    private lateinit var emptyView: TextView
    private lateinit var list: RecyclerView
    private val extensions = mutableListOf<WebExtension>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_extensions)

        list = findViewById(R.id.extensionsList)
        emptyView = findViewById(R.id.extensionsEmpty)
        adapter = ExtAdapter()
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<ImageButton>(R.id.extensionsBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnInstallExt).setOnClickListener { showInstallDialog() }

        loadExtensions()
    }

    private fun loadExtensions() {
        val runtime = GeckoEngine.getRuntime(this) ?: return
        runtime.webExtensionController.list().accept({ list ->
            extensions.clear()
            val filtered = list?.filter { it.id != "uh-bridge@harvester.app" && !it.isBuiltIn } ?: emptyList()
            extensions.addAll(filtered)
            adapter.submit(extensions)
            emptyView.visibility = if (extensions.isEmpty()) View.VISIBLE else View.GONE
            list.visibility = if (extensions.isEmpty()) View.GONE else View.VISIBLE
        }, { err ->
            Toast.makeText(this, "Failed to load extensions", Toast.LENGTH_SHORT).show()
        })
    }

    private fun showInstallDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "https://addons.mozilla.org/..."
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Install Extension")
            .setView(input)
            .setPositiveButton("Install") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotBlank()) {
                    val runtime = GeckoEngine.getRuntime(this) ?: return@setPositiveButton
                    runtime.webExtensionController.install(url).accept({ ext ->
                        Toast.makeText(this, "Installed", Toast.LENGTH_SHORT).show()
                        loadExtensions()
                    }, { err ->
                        Toast.makeText(this, "Failed: ${err?.message}", Toast.LENGTH_SHORT).show()
                    })
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun uninstallExtension(ext: WebExtension) {
        val runtime = GeckoEngine.getRuntime(this) ?: return
        runtime.webExtensionController.uninstall(ext).accept({
            Toast.makeText(this, "Uninstalled", Toast.LENGTH_SHORT).show()
            loadExtensions()
        }, {
            Toast.makeText(this, "Failed to uninstall", Toast.LENGTH_SHORT).show()
        })
    }

    inner class ExtAdapter : RecyclerView.Adapter<ExtAdapter.VH>() {
        private val items = mutableListOf<WebExtension>()
        private val iconCache = mutableMapOf<String, Bitmap?>()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.extIcon)
            val name: TextView = v.findViewById(R.id.extName)
            val meta: TextView = v.findViewById(R.id.extMeta)
            val options: ImageButton = v.findViewById(R.id.extOptions)
            val delete: ImageButton = v.findViewById(R.id.extDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_extension, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val ext = items[position]
            val meta = ext.metaData
            h.name.text = meta?.name ?: ext.id
            h.meta.text = "${meta?.version ?: "1.0"} · ${meta?.description ?: "No description"}"

            val extId = ext.id ?: ""
            val cached = iconCache[extId]
            if (cached != null) {
                h.icon.setImageBitmap(cached)
            } else {
                h.icon.setImageResource(R.drawable.ic_extension_rounded)
                if (meta?.iconUrl != null) {
                    Thread {
                        val bmp = try {
                            URL(meta.iconUrl).openStream().use { BitmapFactory.decodeStream(it) }
                        } catch (_: Exception) { null }
                        runOnUiThread {
                            iconCache[extId] = bmp
                            if (bmp != null) h.icon.setImageBitmap(bmp)
                        }
                    }.start()
                }
            }

            h.options.visibility = if (meta?.optionsPageUrl != null) View.VISIBLE else View.GONE
            h.options.setOnClickListener {
                val url = meta?.optionsPageUrl
                if (!url.isNullOrBlank()) {
                    val intent = android.content.Intent(this@ExtensionsActivity, MainActivity::class.java).apply {
                        data = android.net.Uri.parse(url)
                    }
                    startActivity(intent)
                }
            }

            h.delete.setOnClickListener {
                AlertDialog.Builder(this@ExtensionsActivity)
                    .setTitle("Uninstall ${h.name.text}?")
                    .setPositiveButton("Uninstall") { _, _ -> uninstallExtension(ext) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        override fun getItemCount() = items.size

        fun submit(newList: List<WebExtension>) {
            items.clear()
            items.addAll(newList)
            notifyDataSetChanged()
        }
    }
}
