package app.harvester.companion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class LogsActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var metaView: TextView
    private lateinit var scroll: ScrollView
    private var liveListener: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        logView = findViewById(R.id.logView)
        metaView = findViewById(R.id.logMeta)
        scroll = findViewById(R.id.logScroll)

        logView.typeface = Typeface.MONOSPACE
        logView.movementMethod = ScrollingMovementMethod()
        logView.setTextIsSelectable(true)

    findViewById<ImageButton>(R.id.logsBack).setOnClickListener { finish() }
    findViewById<Button>(R.id.logsRefresh).setOnClickListener { refresh() }
    findViewById<Button>(R.id.logsSave).setOnClickListener { save() }
    findViewById<Button>(R.id.logsCopy).setOnClickListener { copy() }
    findViewById<Button>(R.id.logsClear).setOnClickListener {
        UHLog.clear()
        refresh()
    }

    val verboseToggle = findViewById<android.widget.Switch>(R.id.logsVerbose)
    verboseToggle.isChecked = DebugPrefs.verboseLogging(this)
    verboseToggle.setOnCheckedChangeListener { _, checked ->
        DebugPrefs.setVerboseLogging(this, checked)
        UHLog.setVerbose(checked)
        Toast.makeText(this,
            if (checked) "Verbose on" else "Verbose off",
            Toast.LENGTH_SHORT).show()
    }

    refresh()
    }
    private fun refresh() {
        logView.text = UHLog.snapshot()
        val bytes = UHLog.fileSize()
        metaView.text = "${UHLog.size()} lines · $bytes bytes · v${BuildConfig.VERSION_NAME}"
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun save() {
        try {
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val fname = "uh_log_$ts.txt"
            val ok = DownloadHelper.saveTextToDownloads(this, fname, UHLog.snapshot())
            Toast.makeText(this, if (ok) "Saved to Downloads/$fname" else "Save failed",
                Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copy() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("UH Logs", UHLog.snapshot()))
        Toast.makeText(this, "Copied ${UHLog.size()} lines", Toast.LENGTH_SHORT).show()
    }

    override fun onStart() {
        super.onStart()
        val fn: (String) -> Unit = {
            runOnUiThread {
                logView.append("\n$it")
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        liveListener = fn
        UHLog.addListener(fn)
    }

    override fun onStop() {
        super.onStop()
        liveListener?.let { UHLog.removeListener(it) }
        liveListener = null
    }

    override fun onPause() {
        super.onPause()
        UHLog.onActivityPause(this)
    }
}
