package app.harvester.companion.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.harvester.companion.UHApp

@Composable
fun BrowserFragment() {
    val context = LocalContext.current
    val components = (context.applicationContext as UHApp).components

    AndroidView(
        factory = { ctx ->
            components.engine.createView(ctx).asView()
        },
        modifier = Modifier.fillMaxSize()
    )
}
