package app.harvester.companion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LogsActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var metaView: TextView
    private lateinit var scroll: ScrollView
    private var liveListener: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0A0D14.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Logs"
            setTextColor(0xFFE6EDF5.toInt())
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })

        metaView = TextView(this).apply {
            setTextColor(0xFF8296AD.toInt())
            textSize = 11f
            setPadding(0, dp(4), 0, dp(12))
        }
        root.addView(metaView)

        scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF0F1520.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(0xFFB8C4D4.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
        }
        scroll.addView(logView)
        root.addView(scroll)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        fun mk(label: String, color: Int, fn: () -> Unit) = Button(this).apply {
            text = label
            textSize = 11f
            setTextColor(color)
            setBackgroundColor(0xFF141B28.toInt())
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                leftMargin = dp(2); rightMargin = dp(2)
            }
            setOnClickListener { fn() }
        }
        // Verbose button removed — logging is always verbose.
        btnRow.addView(mk("REFRESH", 0xFFE6EDF5.toInt()) { refresh() })
        btnRow.addView(mk("SAVE", 0xFFE6EDF5.toInt()) { save() })
        btnRow.addView(mk("COPY", 0xFFE6EDF5.toInt()) { copy() })
        btnRow.addView(mk("CLEAR", 0xFFF87171.toInt()) { UHLog.clear(); refresh() })
        root.addView(btnRow)

        val close = Button(this).apply {
            text = "CLOSE"
            textSize = 13f
            setTextColor(0xFF0A0D14.toInt())
            setBackgroundColor(0xFF7DD3FC.toInt())
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(10) }
            setOnClickListener { finish() }
        }
        root.addView(close)

        setContentView(root)
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
            val body = UHLog.snapshot()
            val ok = DownloadHelper.saveTextToDownloads(this, fname, body)
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
