package app.harvester.companion.data.downloads

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import mozilla.components.browser.state.state.content.DownloadState

@Entity(tableName = "downloads")
internal data class UhDownloadEntity(
    @PrimaryKey @ColumnInfo(name = "id") var id: String,
    @ColumnInfo(name = "url") var url: String,
    @ColumnInfo(name = "file_name") var fileName: String?,
    @ColumnInfo(name = "content_type") var contentType: String?,
    @ColumnInfo(name = "content_length") var contentLength: Long?,
    @ColumnInfo(name = "status") var status: DownloadState.Status,
    @ColumnInfo(name = "directory_path") var directoryPath: String,
    @ColumnInfo(name = "created_at") var createdAt: Long,
    @ColumnInfo(name = "etag") val etag: String?,
) {

    internal fun toDownloadState(): DownloadState {
        return DownloadState(
            url,
            fileName,
            contentType,
            contentLength,
            currentBytesCopied = 0,
            status = status,
            userAgent = null,
            directoryPath = directoryPath,
            referrerUrl = null,
            skipConfirmation = false,
            id = id,
            sessionId = null,
            createdTime = createdAt,
            etag = etag,
        )
    }
}

internal fun DownloadState.toUhDownloadEntity(): UhDownloadEntity {
    val isDataURL = url.startsWith("data:")
    val sanitizedURL = if (isDataURL) "" else url

    return UhDownloadEntity(
        id,
        sanitizedURL,
        fileName,
        contentType,
        contentLength,
        status = status,
        directoryPath = directoryPath,
        createdAt = createdTime,
        etag = etag,
    )
}
