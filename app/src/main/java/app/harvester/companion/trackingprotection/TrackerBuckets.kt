package app.harvester.companion.trackingprotection

import java.util.EnumMap
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy.TrackingCategory
import mozilla.components.concept.engine.content.blocking.TrackerLog
import app.harvester.companion.trackingprotection.TrackingProtectionCategory.CROSS_SITE_TRACKING_COOKIES
import app.harvester.companion.trackingprotection.TrackingProtectionCategory.CRYPTOMINERS
import app.harvester.companion.trackingprotection.TrackingProtectionCategory.FINGERPRINTERS
import app.harvester.companion.trackingprotection.TrackingProtectionCategory.SOCIAL_MEDIA_TRACKERS
import app.harvester.companion.trackingprotection.TrackingProtectionCategory.TRACKING_CONTENT

typealias BucketMap = Map<TrackingProtectionCategory, List<TrackerLog>>

class TrackerBuckets {
    private var trackers = emptyList<TrackerLog>()
    data class BucketedTrackerLog(val blockedBucketMap: BucketMap, val loadedBucketMap: BucketMap)
    var buckets: BucketedTrackerLog = BucketedTrackerLog(emptyMap(), emptyMap())
        private set

    fun updateIfNeeded(newTrackers: List<TrackerLog>) {
        if (newTrackers != trackers) {
            trackers = newTrackers
            buckets = putTrackersInBuckets(newTrackers)
        }
    }

    fun blockedIsEmpty() = buckets.blockedBucketMap.isEmpty()
    fun loadedIsEmpty() = buckets.loadedBucketMap.isEmpty()
    fun get(key: TrackingProtectionCategory, blocked: Boolean) = if (blocked) buckets.blockedBucketMap[key].orEmpty() else buckets.loadedBucketMap[key].orEmpty()

    companion object {
        private fun putTrackersInBuckets(list: List<TrackerLog>): BucketedTrackerLog {
            val blockedMap = createMap()
            val loadedMap = createMap()
            for (item in list) {
                primaryBlockedBucket(item)?.let { bucket -> blockedMap.addTrackerHost(bucket, item) }
            }
            return BucketedTrackerLog(blockedMap, loadedMap)
        }

        private fun primaryBlockedBucket(item: TrackerLog): TrackingProtectionCategory? =
            when {
                TrackingCategory.FINGERPRINTING in item.blockedCategories -> FINGERPRINTERS
                TrackingCategory.CRYPTOMINING in item.blockedCategories -> CRYPTOMINERS
                TrackingCategory.MOZILLA_SOCIAL in item.blockedCategories -> SOCIAL_MEDIA_TRACKERS
                TrackingCategory.SCRIPTS_AND_SUB_RESOURCES in item.blockedCategories -> TRACKING_CONTENT
                item.cookiesHasBeenBlocked -> CROSS_SITE_TRACKING_COOKIES
                else -> null
            }

        private fun createMap() = EnumMap<TrackingProtectionCategory, MutableList<TrackerLog>>(TrackingProtectionCategory::class.java)

        private fun MutableMap<TrackingProtectionCategory, MutableList<TrackerLog>>.addTrackerHost(key: TrackingProtectionCategory, tracker: TrackerLog) {
            getOrPut(key) { mutableListOf() }.add(tracker)
        }
    }
}
