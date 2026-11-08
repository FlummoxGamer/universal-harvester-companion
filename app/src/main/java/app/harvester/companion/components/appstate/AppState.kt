package app.harvester.companion.components.appstate

import mozilla.components.lib.state.State
import app.harvester.companion.components.appstate.search.SearchState
import app.harvester.companion.components.appstate.snackbar.SnackbarState

data class AppState(
    val isForeground: Boolean = true,
    val firstFrameDrawn: Boolean = false,
    val mode: BrowsingMode = BrowsingMode.Normal,
    val orientation: OrientationMode = OrientationMode.Undefined,
    val standardSnackbarError: StandardSnackbarError? = null,
    val snackbarState: SnackbarState = SnackbarState.None(),
    val supportedMenuNotifications: Set<SupportedMenuNotifications> = emptySet(),
    val searchState: SearchState = SearchState.EMPTY,
    val isTabsTrayVisible: Boolean = false,
) : State
