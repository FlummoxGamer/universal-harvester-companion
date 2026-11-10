package app.harvester.companion.data.downloads

import androidx.annotation.DrawableRes
import androidx.annotation.FloatRange
import androidx.annotation.StringRes
import app.harvester.companion.R

sealed interface UhDownloadListItem

data class UhFileItem(
    val id: String,
    val url: String,
    val fileName: String?,
    val filePath: String,
    val directoryPath: String,
    val displayedShortUrl: String,
    val contentType: String?,
    val status: Status,
    val timeCategory: TimeCategory,
    val description: String,
) : UhDownloadListItem {

    @DrawableRes val icon: Int = getIcon()

    val matchingContentTypeFilter: ContentTypeFilter
        get() = (ContentTypeFilter.interestingContentTypes).first { type -> type.predicate(contentType) }

    val stringToMatchForSearchQuery: String
        get() = "$fileName $displayedShortUrl"

    enum class ContentTypeFilter(
        @param:StringRes val stringRes: Int,
        val predicate: (String?) -> Boolean,
    ) {
        All(
            stringRes = R.string.download_content_type_filter_all,
            predicate = { true },
        ),
        Image(
            stringRes = R.string.download_content_type_filter_image,
            predicate = { it?.startsWith("image/") == true },
        ),
        Video(
            stringRes = R.string.download_content_type_filter_video,
            predicate = { it?.startsWith("video/") == true },
        ),
        Document(
            stringRes = R.string.download_content_type_filter_document,
            predicate = {
                it?.startsWith("text/") == true ||
                    it in
                        listOf(
                            "application/vnd.ms-excel",
                            "application/msword",
                            "application/vnd.ms-powerpoint",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                            "application/vnd.oasis.opendocument.text",
                            "application/vnd.oasis.opendocument.spreadsheet",
                            "application/vnd.oasis.opendocument.presentation",
                            "application/pdf",
                            "application/rtf",
                            "application/epub+zip",
                            "application/vnd.amazon.ebook",
                            "application/xml",
                            "application/json",
                            "application/vnd.apple.keynote",
                            "application/x-abiword",
                        )
            },
        ),
        Other(
            stringRes = R.string.download_content_type_filter_other_1,
            predicate = { !Image.predicate(it) && !Video.predicate(it) && !Document.predicate(it) },
        );

        companion object {
            val interestingContentTypes = entries - All
        }
    }

    sealed interface Status {
        data object Initiated : Status
        data class Downloading(@param:FloatRange(from = 0.0, to = 1.0) val progress: Float?) : Status
        data class Paused(@param:FloatRange(from = 0.0, to = 1.0) val progress: Float?) : Status
        data object Cancelled : Status
        data object Failed : Status
        data object Completed : Status
    }
}

data class UhHeaderItem(val timeCategory: TimeCategory) : UhDownloadListItem

enum class TimeCategory(@param:StringRes val stringRes: Int) {
    IN_PROGRESS(R.string.download_header_in_progress),
    TODAY(R.string.download_time_period_today),
    YESTERDAY(R.string.download_time_period_yesterday),
    LAST_7_DAYS(R.string.download_time_period_last_7_days),
    LAST_30_DAYS(R.string.download_time_period_last_30_days),
    OLDER(R.string.download_time_period_older),
}
