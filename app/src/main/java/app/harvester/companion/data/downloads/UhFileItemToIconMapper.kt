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
        contentType.contains("image/") -> R.drawable.ic_uh_file_image
        contentType.contains("audio/") -> R.drawable.ic_uh_file_audio
        contentType.contains("video/") -> R.drawable.ic_uh_file_video
        contentType.contains("application/") -> checkForApplicationArchiveSubtypes(contentType)
        contentType.contains("text/") -> R.drawable.ic_uh_file_document
        else -> null
    }
}

private fun checkForApplicationArchiveSubtypes(contentType: String): Int? {
    return when {
        contentType.contains("rar") -> R.drawable.ic_uh_file_zip
        contentType.contains("zip") -> R.drawable.ic_uh_file_zip
        contentType.contains("7z") -> R.drawable.ic_uh_file_zip
        contentType.contains("tar") -> R.drawable.ic_uh_file_zip
        contentType.contains("freearc") -> R.drawable.ic_uh_file_zip
        contentType.contains("octet-stream") -> null
        contentType.contains("vnd.android.package-archive") -> null
        else -> R.drawable.ic_uh_file_document
    }
}

private fun getIconCornerCases(fileName: String?): Int {
    return when {
        fileName?.endsWith("apk") == true -> R.drawable.ic_uh_file_apk
        fileName?.endsWith("zip") == true -> R.drawable.ic_uh_file_zip
        fileName?.endsWith("pdf") == true -> R.drawable.ic_uh_file_document
        else -> R.drawable.ic_uh_file_default
    }
}
