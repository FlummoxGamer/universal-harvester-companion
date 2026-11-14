package app.harvester.companion.utils

import android.content.Context
import mozilla.components.concept.engine.permission.SitePermissions

class Settings(val context: Context) {
    val isLnaFeatureEnabled: Boolean get() = false
    fun getSitePermissionsCustomSettingsRules(): mozilla.components.feature.sitepermissions.SitePermissionsRules = mozilla.components.feature.sitepermissions.SitePermissionsRules()
}
