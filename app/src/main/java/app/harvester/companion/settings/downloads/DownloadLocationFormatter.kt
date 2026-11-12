package app.harvester.companion.settings.downloads

interface DownloadLocationFormatter {
    fun getFriendlyPath(uriString: String): String
}
