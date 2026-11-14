package app.harvester.companion.settings.trustpanel.store

import androidx.annotation.StringRes
import java.security.cert.X509Certificate
import mozilla.components.browser.state.state.SessionState
import mozilla.components.concept.engine.permission.SitePermissions
import mozilla.components.concept.engine.permission.SitePermissions.AutoplayStatus
import mozilla.components.lib.state.State
import app.harvester.companion.R
import app.harvester.companion.settings.PhoneFeature
import app.harvester.companion.trackingprotection.TrackerBuckets
import app.harvester.companion.trackingprotection.TrackingProtectionCategory

typealias WebsitePermissionsState = Map<PhoneFeature, WebsitePermission>

data class TrustPanelState(
    val baseDomain: String? = null,
    val isTrackingProtectionEnabled: Boolean = true,
    val numberOfTrackersBlocked: Int = 0,
    val bucketedTrackers: TrackerBuckets = TrackerBuckets(),
    val detailedTrackerCategory: TrackingProtectionCategory? = null,
    val sessionState: SessionState? = null,
    val sitePermissions: SitePermissions? = null,
    val websiteInfoState: WebsiteInfoState = WebsiteInfoState(),
    val websitePermissionsState: WebsitePermissionsState = mapOf(),
    val ipProtectionMenuState: app.harvester.companion.components.menu.store.IPProtectionMenuState = app.harvester.companion.components.menu.store.IPProtectionMenuState(),
) : State

data class WebsiteInfoState(
    val isSecured: Boolean = true,
    val websiteUrl: String = "",
    val websiteTitle: String = "",
    val certificate: X509Certificate? = null,
    val qwac: X509Certificate? = null,
) {
    val certificateDetails: CertificateDetails? by lazy { certificate?.toCertificateDetails() }
    val isQwac: Boolean get() = qwac != null
}

sealed class WebsitePermission(
    open val isVisible: Boolean,
    open val deviceFeature: PhoneFeature,
) {
    data class Autoplay(
        val autoplayValue: AutoplayValue,
        override val isVisible: Boolean,
        override val deviceFeature: PhoneFeature,
    ) : WebsitePermission(isVisible, deviceFeature)

    data class Toggleable(
        val isEnabled: Boolean,
        val isBlockedByAndroid: Boolean,
        override val isVisible: Boolean,
        override val deviceFeature: PhoneFeature,
    ) : WebsitePermission(isVisible, deviceFeature)
}

enum class AutoplayValue(
    @param:StringRes val title: Int,
    val autoplayAudibleStatus: AutoplayStatus,
    val autoplayInaudibleStatus: AutoplayStatus,
) {
    AUTOPLAY_ALLOW_ALL(
        title = R.string.download_content_type_filter_all,
        autoplayAudibleStatus = AutoplayStatus.ALLOWED,
        autoplayInaudibleStatus = AutoplayStatus.ALLOWED,
    ),
    AUTOPLAY_BLOCK_ALL(
        title = R.string.download_content_type_filter_image,
        autoplayAudibleStatus = AutoplayStatus.BLOCKED,
        autoplayInaudibleStatus = AutoplayStatus.BLOCKED,
    ),
    AUTOPLAY_BLOCK_AUDIBLE(
        title = R.string.download_content_type_filter_video,
        autoplayAudibleStatus = AutoplayStatus.BLOCKED,
        autoplayInaudibleStatus = AutoplayStatus.ALLOWED,
    ),
}
