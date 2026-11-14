package app.harvester.companion.settings

import mozilla.components.concept.engine.permission.SitePermissions

fun SitePermissions.Status.isAllowed() = this == SitePermissions.Status.ALLOWED
fun SitePermissions.Status.doNotAskAgain() = this != SitePermissions.Status.NO_DECISION
