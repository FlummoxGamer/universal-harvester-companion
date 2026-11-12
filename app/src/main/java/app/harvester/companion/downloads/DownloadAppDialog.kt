package app.harvester.companion.downloads

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import mozilla.components.feature.downloads.ui.DownloaderApp
import app.harvester.companion.R
import app.harvester.companion.theme.UHTheme

internal fun createDownloadAppDialog(
    context: Context,
    downloaderApps: List<DownloaderApp>,
    onAppSelected: (DownloaderApp) -> Unit,
    onDismiss: () -> Unit,
): AlertDialog {
    lateinit var dialog: AlertDialog

    val composeView = ComposeView(context).apply {
        setContent {
            UHTheme {
                DownloaderAppList(
                    apps = downloaderApps,
                    onAppSelected = { selectedApp ->
                        onAppSelected(selectedApp)
                        dialog.dismiss()
                    },
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
            }
        }
    }

    val builder = MaterialAlertDialogBuilder(context)
        .setTitle(context.getString(R.string.download_app_dialog_title))
        .setView(composeView)
        .setOnDismissListener { onDismiss.invoke() }

    dialog = builder.create()
    return dialog
}

@Composable
private fun DownloaderAppList(
    apps: List<DownloaderApp>,
    onAppSelected: (DownloaderApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        items(apps) { app ->
            DownloaderAppItem(
                iconBitmap = app.resolver.loadIcon(LocalContext.current.packageManager).toBitmap().asImageBitmap(),
                appName = app.name,
                onAppSelected = { onAppSelected(app) },
            )
        }
    }
}

@Composable
private fun DownloaderAppItem(
    iconBitmap: androidx.compose.ui.graphics.ImageBitmap,
    appName: String,
    onAppSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable { onAppSelected() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            bitmap = iconBitmap,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = appName,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
