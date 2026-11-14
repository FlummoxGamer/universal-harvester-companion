package app.harvester.companion.settings

import mozilla.components.concept.engine.permission.SitePermissions
import mozilla.components.feature.sitepermissions.SitePermissionsRules
import app.harvester.companion.settings.PhoneFeature

fun SitePermissions.toggle(feature: PhoneFeature): SitePermissions {
    return this
}

fun PhoneFeature.getStatus(sitePermissions: SitePermissions?, settings: app.harvester.companion.utils.Settings): SitePermissions.Status {
    return SitePermissions.Status.NO_DECISION
}

fun SitePermissionsRules.toSitePermissions(origin: String): SitePermissions {
    return SitePermissions(origin = origin, isPrivate = false)
}
