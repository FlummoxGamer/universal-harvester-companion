package app.harvester.companion.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.harvester.companion.R

enum class PhoneFeature(val androidPermissionsList: Array<String>) {
    CAMERA(arrayOf(android.Manifest.permission.CAMERA)),
    LOCATION(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)),
    MICROPHONE(arrayOf(android.Manifest.permission.RECORD_AUDIO)),
    NOTIFICATION(emptyArray()),
    AUTOPLAY(emptyArray()),
    AUTOPLAY_AUDIBLE(emptyArray()),
    AUTOPLAY_INAUDIBLE(emptyArray()),
    PERSISTENT_STORAGE(emptyArray()),
    MEDIA_KEY_SYSTEM_ACCESS(emptyArray()),
    CROSS_ORIGIN_STORAGE_ACCESS(emptyArray()),
    LOCAL_DEVICE_ACCESS(emptyArray()),
    LOCAL_NETWORK_ACCESS(emptyArray());

    @StringRes fun getLabelId(): Int = R.string.app_name
    @DrawableRes fun getIconId(): Int {
        return when (this) {
            CAMERA -> R.drawable.uh_ic_camera_24
            LOCATION -> R.drawable.uh_ic_location_24
            MICROPHONE -> R.drawable.uh_ic_microphone_24
            NOTIFICATION -> R.drawable.uh_ic_notification_24
            PERSISTENT_STORAGE -> R.drawable.uh_ic_storage_24
            CROSS_ORIGIN_STORAGE_ACCESS -> R.drawable.uh_ic_cookies_24
            MEDIA_KEY_SYSTEM_ACCESS -> R.drawable.uh_ic_link_24
            AUTOPLAY -> R.drawable.uh_ic_autoplay_24
            LOCAL_DEVICE_ACCESS -> R.drawable.uh_ic_device_desktop_24
            LOCAL_NETWORK_ACCESS -> R.drawable.uh_ic_local_network_24
            else -> R.drawable.ic_shield
        }
    }
    fun getStatus(sitePermissions: mozilla.components.concept.engine.permission.SitePermissions?, settings: Any?): mozilla.components.concept.engine.permission.SitePermissions.Status = mozilla.components.concept.engine.permission.SitePermissions.Status.NO_DECISION
}
