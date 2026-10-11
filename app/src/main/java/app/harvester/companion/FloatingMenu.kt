package app.harvester.companion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import app.harvester.companion.R

object FloatingMenu {

    fun show(activity: Activity, webView: HarvesterWebView, recreateWebView: (String) -> Unit) {
        val popup = PopupMenu(activity, activity.findViewById(R.id.fab))
        popup.menu.add(0, 1, 0, "Reload Script")
        popup.menu.add(0, 2, 1, "Clear All State")
        popup.menu.add(0, 3, 2, "Settings")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    recreateWebView(webView.url ?: "about:blank")
                    true
                }
                2 -> { confirmClear(activity, webView, recreateWebView); true }
                3 -> {
                    activity.startActivity(Intent(activity, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun confirmClear(activity: Activity, webView: HarvesterWebView, recreate: (String) -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("Clear all state?")
            .setMessage("Wipes cookies, GM values, cached script, and page storage.")
            .setPositiveButton("Clear") { _, _ -> clearAll(activity, webView, recreate) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearAll(activity: Activity, webView: HarvesterWebView, recreate: (String) -> Unit) {
        try {
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
            activity.getSharedPreferences("uh_gm", Context.MODE_PRIVATE).edit().clear().apply()
            // Delete cached userscript
            java.io.File(activity.filesDir, "scripts/current.js").delete()
            java.io.File(activity.filesDir, "scripts/meta.json").delete()
        } catch (_: Exception) {}
        Toast.makeText(activity, R.string.toast_state_cleared, Toast.LENGTH_SHORT).show()
        recreate("about:blank")
    }
}
