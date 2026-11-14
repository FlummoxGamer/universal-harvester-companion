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
    @DrawableRes fun getIconId(): Int = R.drawable.ic_shield
    fun getStatus(sitePermissions: mozilla.components.concept.engine.permission.SitePermissions?, settings: Any?): mozilla.components.concept.engine.permission.SitePermissions.Status = mozilla.components.concept.engine.permission.SitePermissions.Status.NO_DECISION
}
