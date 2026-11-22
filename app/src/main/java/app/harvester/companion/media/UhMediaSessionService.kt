package app.harvester.companion.media

import app.harvester.companion.UHApp
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.base.crash.CrashReporting
import mozilla.components.feature.media.service.AbstractMediaSessionService
import mozilla.components.support.base.android.NotificationsDelegate

/**
 * Native foreground Android MediaSessionService powered by Mozilla Android Components `feature-media`.
 * Displays lock-screen & notification shade media controls (artwork, title, site origin,
 * play/pause, seek, next/previous, Bluetooth/headset controls) and keeps background playback alive.
 */
class UhMediaSessionService : AbstractMediaSessionService() {

    override val store: BrowserStore by lazy {
        (application as UHApp).components.store
    }

    override val crashReporter: CrashReporting? = null

    override val notificationsDelegate: NotificationsDelegate by lazy {
        (application as UHApp).components.notificationsDelegate
    }
}
