package app.harvester.companion.privacy

import java.util.Locale

enum class ShieldLevel(val code: Int, val label: String, val summary: String) {
    OFF(0, "Off", "Ad and tracker blocking disabled"),
    STANDARD(1, "Standard", "Blocks 3rd-party ads, trackers, annoyances & LAN port scans with minimal site impact"),
    AGGRESSIVE(2, "Aggressive", "Full-power ad, CNAME tracker, annoyance, LAN guard & anti-adblock defense");

    companion object {
        fun fromCode(code: Int): ShieldLevel = entries.firstOrNull { it.code == code } ?: AGGRESSIVE
    }
}

enum class FilterPackTier {
    CORE,
    NUCLEAR,
    SOCIAL,
    REGIONAL,
    CUSTOM,
}

data class FilterListDef(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val tier: FilterPackTier,
    val defaultEnabled: Boolean,
    val isTrackerList: Boolean = false,
)

data class FilterListStatus(
    val def: FilterListDef,
    val enabled: Boolean,
    val ruleCount: Int = 0,
    val lastUpdatedMs: Long = 0L,
    val lastError: String? = null,
)

enum class FilterCategoryTier {
    CORE,
    EXTRA,
}

data class FilterCategoryDef(
    val id: String,
    val title: String,
    val description: String,
    val tier: FilterCategoryTier,
    val iconRes: Int,
    val listIds: List<String>,
)

data class FilterCategoryStatus(
    val def: FilterCategoryDef,
    val enabled: Boolean,
    val ruleCount: Int = 0,
)

data class ShieldStatsSnapshot(
    val adsBlocked: Long = 0L,
    val trackersBlocked: Long = 0L,
    val estimatedTimeSavedMs: Long = 0L,
) {
    val totalBlocked: Long get() = adsBlocked + trackersBlocked

    fun formattedTimeSaved(): String {
        val totalSec = estimatedTimeSavedMs / 1000L
        return when {
            totalSec < 60L -> "${totalSec}s"
            totalSec < 3600L -> {
                val mins = totalSec / 60L
                val secs = totalSec % 60L
                if (mins < 10L && secs >= 10L) "${mins}m ${secs}s" else "${mins}m"
            }
            else -> {
                val hrs = totalSec / 3600.0
                String.format(Locale.US, "%.1fh", hrs)
            }
        }
    }
}

data class JsBlockedPageState(
    val host: String = "",
    val totalBlocked: Int = 0,
    val blockedDomains: Map<String, Int> = emptyMap(),
    val updatedAtMs: Long = 0L,
)

data class BlockedRedirectEvent(
    val id: Long = 0L,
    val sourceHost: String = "",
    val targetUrl: String = "",
    val targetHost: String = "",
    val isPopupTab: Boolean = false,
)

enum class RequestLogAction(val label: String, val isBlocked: Boolean) {
    ALLOWED("OK", false),
    BLOCKED_AD("AD", true),
    BLOCKED_TRACKER("TRACKER", true),
    BLOCKED_CNAME("CNAME", true),
    BLOCKED_LAN("LAN", true);

    companion object {
        fun fromWire(raw: String): RequestLogAction = when (raw) {
            "BLOCKED_AD" -> BLOCKED_AD
            "BLOCKED_TRACKER" -> BLOCKED_TRACKER
            "BLOCKED_CNAME" -> BLOCKED_CNAME
            "BLOCKED_LAN" -> BLOCKED_LAN
            else -> ALLOWED
        }
    }
}

data class RequestLogEntry(
    val id: Long = 0L,
    val pageHost: String = "",
    val reqHost: String = "",
    val pathHint: String = "",
    val resourceType: String = "",
    val action: RequestLogAction = RequestLogAction.ALLOWED,
    val ruleSource: String = "",
    val timestampMs: Long = 0L,
)

data class HomographWarning(
    val asciiHost: String,
    val unicodeHost: String,
    val reason: String,
)

