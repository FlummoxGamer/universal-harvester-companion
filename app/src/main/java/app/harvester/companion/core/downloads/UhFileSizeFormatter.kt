package app.harvester.companion.core.downloads

import android.content.Context
import android.text.format.Formatter

interface UhFileSizeFormatter {
    fun formatSizeInBytes(sizeInBytes: Long): String
}

class UhDefaultFileSizeFormatter(private val context: Context) : UhFileSizeFormatter {
    override fun formatSizeInBytes(sizeInBytes: Long): String = Formatter.formatFileSize(context, sizeInBytes)
}
