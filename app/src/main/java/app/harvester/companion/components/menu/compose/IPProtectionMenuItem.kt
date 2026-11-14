package app.harvester.companion.components.menu.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import app.harvester.companion.R
import app.harvester.companion.components.menu.store.IPProtectionMenuState

@Composable
internal fun IPProtectionMenuItem(
    state: IPProtectionMenuState,
    onToggle: () -> Unit,
    onNavigate: () -> Unit,
) {
    MenuItem(
        label = "IP Protection",
        beforeIconPainter = painterResource(R.drawable.ic_globe),
        onClick = onToggle,
    )
}
