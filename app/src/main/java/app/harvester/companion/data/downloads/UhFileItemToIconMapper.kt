package app.harvester.companion.data.downloads

import androidx.annotation.DrawableRes
import app.harvester.companion.R

@DrawableRes
fun UhFileItem.getIcon(): Int {
    return contentType?.let { contentType ->
        getIconFromContentType(contentType)
    } ?: getIconCornerCases(fileName)
}

private fun getIconFromContentType(contentType: String): Int? {
    return when {
        contentType.contains("image/") -> R.drawable.fx_ic_file_image_24
        contentType.contains("audio/") -> R.drawable.fx_ic_file_audio_24
        contentType.contains("video/") -> R.drawable.fx_ic_file_video_24
        contentType.contains("application/") -> checkForApplicationArchiveSubtypes(contentType)
        contentType.contains("text/") -> R.drawable.fx_ic_file_document_24
        else -> null
    }
}

private fun checkForApplicationArchiveSubtypes(contentType: String): Int? {
    return when {
        contentType.contains("rar") -> R.drawable.fx_ic_file_archive_24
        contentType.contains("zip") -> R.drawable.fx_ic_file_archive_24
        contentType.contains("7z") -> R.drawable.fx_ic_file_archive_24
        contentType.contains("tar") -> R.drawable.fx_ic_file_archive_24
        contentType.contains("freearc") -> R.drawable.fx_ic_file_archive_24
        contentType.contains("octet-stream") -> null
        contentType.contains("vnd.android.package-archive") -> R.drawable.fx_ic_file_apk_24
        else -> R.drawable.fx_ic_file_document_24
    }
}

private fun getIconCornerCases(fileName: String?): Int {
    val lower = fileName?.lowercase().orEmpty()
    return when {
        lower.endsWith(".apk") -> R.drawable.fx_ic_file_apk_24
        lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") || lower.endsWith(".tar") -> R.drawable.fx_ic_file_archive_24
        lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".txt") -> R.drawable.fx_ic_file_document_24
        lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") -> R.drawable.fx_ic_file_video_24
        lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac") || lower.endsWith(".m4a") -> R.drawable.fx_ic_file_audio_24
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") -> R.drawable.fx_ic_file_image_24
        else -> R.drawable.fx_ic_file_generic_24
    }
}
