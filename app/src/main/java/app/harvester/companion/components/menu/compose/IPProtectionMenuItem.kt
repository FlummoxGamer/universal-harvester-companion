package app.harvester.companion.components.menu.compose

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import app.harvester.companion.R
import app.harvester.companion.components.menu.store.IPProtectionMenuState
import mozilla.components.feature.ipprotection.store.state.IPProtectionStatus

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
