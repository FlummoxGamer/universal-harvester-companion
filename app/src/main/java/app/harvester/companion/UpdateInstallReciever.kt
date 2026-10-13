package app.harvester.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

class UpdateInstallReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INSTALL_RESULT = "app.harvester.companion.INSTALL_RESULT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // OS is asking the user to confirm. Launch the confirmation activity.
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                        UHLog.throwable("update", "user action launch failed", e)
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                UHLog.d("update", "install success")
            }
            else -> {
                UHLog.e("update", "install failed status=$status message=$message")
                Toast.makeText(
                    context,
                    "Install failed: $message",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
