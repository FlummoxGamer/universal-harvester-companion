package app.harvester.companion

import android.app.DownloadManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class DownloadsActivity : AppCompatActivity() {

    private lateinit var adapter: Adapter
    private lateinit var emptyView: TextView
    private lateinit var list: RecyclerView
    private lateinit var activeContainer: android.widget.LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        list = findViewById(R.id.downloadsList)
        emptyView = findViewById(R.id.downloadsEmpty)
        activeContainer = findViewById(R.id.activeDownloadChip)
        
        adapter = Adapter(
            onClick = { item -> openItem(item) },
            onLongClick = { item -> showItemMenu(item) }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<ImageButton>(R.id.downloadsBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.downloadsMenu).setOnClickListener { showSortMenu() }

        observeActiveDownloads()
        reload()
    }

private fun observeActiveDownloads() {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            DownloadStore.activeDownloads.collect { tasks ->
                activeContainer.removeAllViews()
                if (tasks.isEmpty()) {
                    activeContainer.visibility = View.GONE
                } else {
                    activeContainer.visibility = View.VISIBLE
                    for (task in tasks) {
                        val v = layoutInflater.inflate(R.layout.item_active_download, activeContainer, false)
                        val name = v.findViewById<TextView>(R.id.activeDlName)
                        val status = v.findViewById<TextView>(R.id.activeDlStatus)
                        val pb = v.findViewById<ProgressBar>(R.id.activeDlProgress)
                        val btnToggle = v.findViewById<ImageButton>(R.id.btnTogglePause)

                        name.text = task.filename
                        val size = "${formatSize(task.bytesDownloaded)} / ${formatSize(task.totalBytes)}"
                        val speed = "${formatSize(task.speed)}/s"
                        val eta = if (task.etaSeconds > 0) "${task.etaSeconds} sec left" else "Calculating..."
                        
                        status.text = when (task.status) {
                            DownloadStore.Status.PAUSED -> "Paused • $size"
                            DownloadStore.Status.FAILED -> "Failed • $size"
                            else -> "$size • $speed • $eta"
                        }
                        
                        pb.max = 100
                        pb.progress = if (task.totalBytes > 0) ((task.bytesDownloaded * 100) / task.totalBytes).toInt() else 0

                        when (task.status) {
                            DownloadStore.Status.RUNNING -> {
                                btnToggle.setImageResource(android.R.drawable.ic_media_pause)
                                btnToggle.setOnClickListener { DownloadEngine.pauseDownload(this@DownloadsActivity, task.id) }
                            }
                            DownloadStore.Status.PAUSED -> {
                                btnToggle.setImageResource(android.R.drawable.ic_media_play)
                                btnToggle.setOnClickListener { DownloadEngine.resumeDownload(this@DownloadsActivity, task.id) }
                            }
                            DownloadStore.Status.FAILED -> {
                                btnToggle.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                                btnToggle.setOnClickListener { DownloadEngine.cancelDownload(this@DownloadsActivity, task.id) }
                            }
                            else -> {}
                        }

                        activeContainer.addView(v)
                    }
                }
            }
        }
    }
}
    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val items = DownloadsQuery.list(this)
        adapter.submit(items)
        val hasActive = DownloadStore.activeDownloads.value.isNotEmpty()
        emptyView.visibility = if (items.isEmpty() && !hasActive) View.VISIBLE else View.GONE
        list.visibility = if (items.isEmpty() && !hasActive) View.GONE else View.VISIBLE
    }

    private fun formatSize(b: Long): String = when {
        b < 0 -> "?"
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / (1024.0 * 1024))
        else -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
    }

    private fun showItemMenu(item: DownloadsQuery.Item) {
        val opts = arrayOf("Open", "Share", "Rename", "Delete")
        AlertDialog.Builder(this)
            .setTitle(item.filename)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> openItem(item)
                    1 -> shareItem(item)
                    2 -> showRenameDialog(item)
                    3 -> deleteItem(item)
                }
            }
            .show()
    }

    private fun showRenameDialog(item: DownloadsQuery.Item) {
        val view = layoutInflater.inflate(R.layout.dialog_rename, null)
        val input = view.findViewById<android.widget.EditText>(R.id.renameInput)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        input.setText(item.filename)
        input.requestFocus()
        
        val name = item.filename
        val dotIndex = name.lastIndexOf('.')
        if (dotIndex > 0) {
            input.setSelection(0, dotIndex)
        } else {
            input.setSelection(0, name.length)
        }

        view.findViewById<View>(R.id.renameCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.renameOk).setOnClickListener {
            val newName = input.text.toString().trim()
            if (newName.isNotBlank()) {
                renameFile(item, newName)
                dialog.dismiss()
            }
        }
        
        dialog.show()
        dialog.window?.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        input.requestFocus()
    }

    private fun renameFile(item: DownloadsQuery.Item, newName: String) {
        try {
            val uri = DownloadsQuery.uriFor(item.id)
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, newName)
            }
            contentResolver.update(uri, values, null, null)
            android.widget.Toast.makeText(this, "Renamed", android.widget.Toast.LENGTH_SHORT).show()
            reload()
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "Rename failed", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareItem(item: DownloadsQuery.Item) {
        try {
            val uri = DownloadsQuery.uriFor(item.id)
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = item.mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share"
            ))
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "Share failed", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteItem(item: DownloadsQuery.Item) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${item.filename}?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    contentResolver.delete(DownloadsQuery.uriFor(item.id), null, null)
                    android.widget.Toast.makeText(this, "Deleted", android.widget.Toast.LENGTH_SHORT).show()
                    reload()
                } catch (_: Exception) {
                    android.widget.Toast.makeText(this, "Delete failed", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openItem(item: DownloadsQuery.Item) {
        try {
            val uri = DownloadsQuery.uriFor(item.id)
            val mime = contentResolver.getType(uri) ?: item.mime
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            try {
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            } catch (_: Exception) {}
        }
    }

    private fun showSortMenu() {
        val opts = arrayOf("Sort by date", "Sort by size", "Sort by name")
        AlertDialog.Builder(this)
            .setItems(opts) { _, which ->
                DownloadsQuery.setSort(which)
                reload()
            }
            .show()
    }

    class Adapter(
        private val onClick: (DownloadsQuery.Item) -> Unit,
        private val onLongClick: (DownloadsQuery.Item) -> Unit
    ) : RecyclerView.Adapter<Adapter.VH>() {

        private val items = mutableListOf<DownloadsQuery.Item>()

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.dlName)
            val meta: TextView = v.findViewById(R.id.dlMeta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_download_manager, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val item = items[position]
            h.name.text = item.title.ifBlank { item.filename }
            h.meta.text = "${item.sizeText} • ${item.dateText}"
            h.itemView.setOnClickListener { onClick(item) }
            h.itemView.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }

        override fun getItemCount() = items.size

        fun submit(newList: List<DownloadsQuery.Item>) {
            items.clear()
            items.addAll(newList)
            notifyDataSetChanged()
        }
    }
}
