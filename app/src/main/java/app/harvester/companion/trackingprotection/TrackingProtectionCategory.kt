package app.harvester.companion.trackingprotection

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.harvester.companion.R

enum class TrackingProtectionCategory(
    @param:StringRes val title: Int,
    @param:StringRes val description: Int,
    @param:DrawableRes val icon: Int,
) {
    SOCIAL_MEDIA_TRACKERS(R.string.download_content_type_filter_all, R.string.download_content_type_filter_all, R.drawable.ic_shield),
    CROSS_SITE_TRACKING_COOKIES(R.string.download_content_type_filter_image, R.string.download_content_type_filter_image, R.drawable.ic_shield),
    CRYPTOMINERS(R.string.download_content_type_filter_video, R.string.download_content_type_filter_video, R.drawable.ic_shield),
    FINGERPRINTERS(R.string.download_content_type_filter_document, R.string.download_content_type_filter_document, R.drawable.ic_shield),
    TRACKING_CONTENT(R.string.download_content_type_filter_other_1, R.string.download_content_type_filter_other_1, R.drawable.ic_shield),
}
