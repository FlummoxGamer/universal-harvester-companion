package app.harvester.companion.utils

import android.content.Context
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.settings.PhoneFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mozilla.components.feature.sitepermissions.SitePermissionsRules

class SitePermissionRules(val context: Context) {
    val isLnaFeatureEnabled: Boolean get() = false

    private val _rulesFlow = MutableStateFlow(getSitePermissionsCustomSettingsRules())
    val rulesFlow: StateFlow<SitePermissionsRules> = _rulesFlow.asStateFlow()

    fun notifyRulesChanged() {
        _rulesFlow.value = getSitePermissionsCustomSettingsRules()
    }

    fun getSitePermissionsCustomSettingsRules(): SitePermissionsRules {
        val allowAutoplay = SettingsPrefs.getBool(context, SettingsPrefs.K_AUTOPLAY, false)
        return SitePermissionsRules(
            notification = getAction(PhoneFeature.NOTIFICATION),
            microphone = getAction(PhoneFeature.MICROPHONE),
            location = getAction(PhoneFeature.LOCATION),
            camera = getAction(PhoneFeature.CAMERA),
            autoplayAudible = if (allowAutoplay) {
                SitePermissionsRules.AutoplayAction.ALLOWED
            } else {
                SitePermissionsRules.AutoplayAction.BLOCKED
            },
            autoplayInaudible = SitePermissionsRules.AutoplayAction.ALLOWED,
            persistentStorage = getAction(PhoneFeature.PERSISTENT_STORAGE),
            crossOriginStorageAccess = getAction(PhoneFeature.CROSS_ORIGIN_STORAGE_ACCESS),
            mediaKeySystemAccess = getAction(PhoneFeature.MEDIA_KEY_SYSTEM_ACCESS),
            localDeviceAccess = getAction(PhoneFeature.LOCAL_DEVICE_ACCESS),
            localNetworkAccess = getAction(PhoneFeature.LOCAL_NETWORK_ACCESS),
        )
    }

    fun getAutoplayUserSetting(): Int {
        val allowAutoplay = SettingsPrefs.getBool(context, SettingsPrefs.K_AUTOPLAY, false)
        return if (allowAutoplay) 1 else SettingsPrefs.getInt(context, "autoplay_user_setting", 3)
    }

    private fun getAction(feature: PhoneFeature): SitePermissionsRules.Action {
        val key = "site_perm_${feature.name.lowercase()}"
        return when (SettingsPrefs.getInt(context, key, 1)) {
            0 -> SitePermissionsRules.Action.BLOCKED
            2 -> SitePermissionsRules.Action.ALLOWED
            else -> SitePermissionsRules.Action.ASK_TO_ALLOW
        }
    }
}
