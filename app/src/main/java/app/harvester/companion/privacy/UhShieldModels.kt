package app.harvester.companion.privacy

import java.util.Locale

enum class ShieldLevel(val code: Int, val label: String, val summary: String) {
    OFF(0, "Off", "Ad and tracker blocking disabled"),
    STANDARD(1, "Standard", "Blocks third-party ads, trackers, and malware with minimal site impact"),
    AGGRESSIVE(2, "Aggressive", "Blocks 1st & 3rd-party ads, CNAME trackers, cosmetics, and anti-adblock scripts");

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
