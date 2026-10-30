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
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class DownloadsActivity : AppCompatActivity() {

    private lateinit var activeAdapter: ActiveAdapter
    private lateinit var completedAdapter: CompletedAdapter
    private lateinit var emptyView: TextView
    private lateinit var list: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        list = findViewById(R.id.downloadsList)
        emptyView = findViewById(R.id.downloadsEmpty)
        
        activeAdapter = ActiveAdapter(
            onPause = { id -> DownloadEngine.pauseDownload(this, id) },
            onResume = { id -> DownloadEngine.resumeDownload(this, id) },
            onCancel = { id -> DownloadEngine.cancelDownload(this, id) }
        )
        completedAdapter = CompletedAdapter(
            onClick = { item -> openItem(item) },
            onLongClick = { item -> showItemMenu(item) }
        )
        
        val concatAdapter = ConcatAdapter(activeAdapter, completedAdapter)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = concatAdapter

        findViewById<ImageButton>(R.id.downloadsBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.downloadsMenu).setOnClickListener { showSortMenu() }

        observeActiveDownloads()
        reload()
    }

    private fun observeActiveDownloads() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadStore.activeDownloads.collect { tasks ->
                    activeAdapter.submit(tasks)
                    updateEmptyState()
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
        completedAdapter.submit(items)
        updateEmptyState()
    }

    private fun updateEmptyState() {
        val hasActive = activeAdapter.itemCount > 0
        val hasCompleted = completedAdapter.itemCount > 0
        emptyView.visibility = if (!hasActive && !hasCompleted) View.VISIBLE else View.GONE
        list.visibility = if (!hasActive && !hasCompleted) View.GONE else View.VISIBLE
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

    class ActiveAdapter(
        private val onPause: (String) -> Unit,
        private val onResume: (String) -> Unit,
        private val onCancel: (String) -> Unit
    ) : RecyclerView.Adapter<ActiveAdapter.VH>() {
        private val items = mutableListOf<DownloadStore.Task>()

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.activeDlName)
            val status: TextView = v.findViewById(R.id.activeDlStatus)
            val pb: ProgressBar = v.findViewById(R.id.activeDlProgress)
            val btnToggle: ImageButton = v.findViewById(R.id.btnTogglePause)
            val btnCancel: ImageButton = v.findViewById(R.id.btnCancel)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_active_download, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val task = items[position]
            h.name.text = task.filename
            val size = "${formatSize(task.bytesDownloaded)} / ${formatSize(task.totalBytes)}"
            val speed = "${formatSize(task.speed)}/s"
            val eta = when {
                task.etaSeconds < 0 -> "Calculating..."
                task.etaSeconds < 60 -> "${task.etaSeconds}s left"
                task.etaSeconds < 3600 -> "${task.etaSeconds / 60}m left"
                else -> "${task.etaSeconds / 3600}h left"
            }
            
            h.status.text = when (task.status) {
                DownloadStore.Status.PENDING -> "Queued..."
                DownloadStore.Status.PAUSED -> "Paused • $size"
                DownloadStore.Status.FAILED -> "Failed • $size"
                DownloadStore.Status.WAITING_FOR_NETWORK -> "Waiting for network • $size"
                else -> "$size • $speed • $eta"
            }
            
            h.pb.max = 100
            h.pb.progress = if (task.totalBytes > 0) ((task.bytesDownloaded * 100) / task.totalBytes).toInt() else 0

            when (task.status) {
                DownloadStore.Status.RUNNING -> {
                    h.btnToggle.setImageResource(android.R.drawable.ic_media_pause)
                    h.btnToggle.setOnClickListener { onPause(task.id) }
                    h.btnToggle.visibility = View.VISIBLE
                }
                DownloadStore.Status.PAUSED -> {
                    h.btnToggle.setImageResource(android.R.drawable.ic_media_play)
                    h.btnToggle.setOnClickListener { onResume(task.id) }
                    h.btnToggle.visibility = View.VISIBLE
                }
                DownloadStore.Status.PENDING -> {
                    h.btnToggle.visibility = View.GONE
                }
                DownloadStore.Status.FAILED -> {
                    h.btnToggle.setImageResource(R.drawable.ic_reload)
                    h.btnToggle.setOnClickListener { onResume(task.id) }
                    h.btnToggle.visibility = View.VISIBLE
                }
                else -> {
                    h.btnToggle.visibility = View.GONE
                }
            }
            
            h.btnCancel.setOnClickListener { onCancel(task.id) }
        }

        override fun getItemCount() = items.size

        fun submit(newList: List<DownloadStore.Task>) {
            items.clear()
            items.addAll(newList)
            notifyDataSetChanged()
        }
    }

    class CompletedAdapter(
        private val onClick: (DownloadsQuery.Item) -> Unit,
        private val onLongClick: (DownloadsQuery.Item) -> Unit
    ) : RecyclerView.Adapter<CompletedAdapter.VH>() {

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

    companion object {
        fun formatSize(b: Long): String = when {
            b <= 0 -> "?"
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
            b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / (1024.0 * 1024))
            else -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
        }
    }
}
