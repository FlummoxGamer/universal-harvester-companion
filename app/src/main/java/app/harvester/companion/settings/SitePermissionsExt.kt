package app.harvester.companion.settings

import mozilla.components.concept.engine.permission.SitePermissions
import mozilla.components.concept.engine.permission.SitePermissionsStorage

fun SitePermissions.toggle(feature: PhoneFeature): SitePermissions {
    return when (feature) {
        PhoneFeature.CAMERA -> copy(camera = camera.toggle())
        PhoneFeature.LOCATION -> copy(location = location.toggle())
        PhoneFeature.MICROPHONE -> copy(microphone = microphone.toggle())
        PhoneFeature.NOTIFICATION -> copy(notification = notification.toggle())
        PhoneFeature.PERSISTENT_STORAGE -> copy(localStorage = localStorage.toggle())
        PhoneFeature.MEDIA_KEY_SYSTEM_ACCESS -> copy(mediaKeySystemAccess = mediaKeySystemAccess.toggle())
        PhoneFeature.CROSS_ORIGIN_STORAGE_ACCESS -> copy(crossOriginStorageAccess = crossOriginStorageAccess.toggle())
        PhoneFeature.LOCAL_DEVICE_ACCESS -> copy(localDeviceAccess = localDeviceAccess.toggle())
        PhoneFeature.LOCAL_NETWORK_ACCESS -> copy(localNetworkAccess = localNetworkAccess.toggle())
        else -> this
    }
}

fun PhoneFeature.toPermission(): SitePermissionsStorage.Permission {
    return when (this) {
        PhoneFeature.MICROPHONE -> SitePermissionsStorage.Permission.MICROPHONE
        PhoneFeature.CAMERA -> SitePermissionsStorage.Permission.CAMERA
        PhoneFeature.NOTIFICATION -> SitePermissionsStorage.Permission.NOTIFICATION
        PhoneFeature.LOCATION -> SitePermissionsStorage.Permission.LOCATION
        PhoneFeature.PERSISTENT_STORAGE -> SitePermissionsStorage.Permission.LOCAL_STORAGE
        PhoneFeature.MEDIA_KEY_SYSTEM_ACCESS -> SitePermissionsStorage.Permission.MEDIA_KEY_SYSTEM_ACCESS
        PhoneFeature.CROSS_ORIGIN_STORAGE_ACCESS -> SitePermissionsStorage.Permission.STORAGE_ACCESS
        PhoneFeature.LOCAL_DEVICE_ACCESS -> SitePermissionsStorage.Permission.LOCAL_DEVICE_ACCESS
        PhoneFeature.LOCAL_NETWORK_ACCESS -> SitePermissionsStorage.Permission.LOCAL_NETWORK_ACCESS
        PhoneFeature.AUTOPLAY_AUDIBLE -> SitePermissionsStorage.Permission.AUTOPLAY_AUDIBLE
        PhoneFeature.AUTOPLAY_INAUDIBLE -> SitePermissionsStorage.Permission.AUTOPLAY_INAUDIBLE
        else -> SitePermissionsStorage.Permission.CAMERA
    }
}

fun PhoneFeature.getStatus(sitePermissions: SitePermissions?, rules: app.harvester.companion.utils.SitePermissionRules? = null): SitePermissions.Status {
    return sitePermissions?.get(this.toPermission()) ?: SitePermissions.Status.NO_DECISION
}
