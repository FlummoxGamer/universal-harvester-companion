package app.harvester.companion.core.downloads

import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.fetch.Client
import mozilla.components.feature.downloads.AbstractFetchDownloadService
import mozilla.components.feature.downloads.DefaultPackageNameProvider
import mozilla.components.feature.downloads.DownloadEstimator
import mozilla.components.feature.downloads.FileSizeFormatter
import mozilla.components.feature.downloads.PackageNameProvider
import mozilla.components.feature.downloads.filewriter.DefaultDownloadFileWriter
import mozilla.components.feature.downloads.filewriter.DownloadFileWriter
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.utils.DefaultDownloadFileUtils
import mozilla.components.support.utils.DownloadFileUtils
import app.harvester.companion.R
import app.harvester.companion.UHApp

class UhDownloadService : AbstractFetchDownloadService() {
    override val httpClient: Client by lazy { (application as UHApp).components.client }
    override val store: BrowserStore by lazy { (application as UHApp).components.store }
    override val style: Style by lazy { Style(R.color.accent) }
    override val notificationsDelegate: NotificationsDelegate by lazy { (application as UHApp).components.notificationsDelegate }
    override val fileSizeFormatter: FileSizeFormatter by lazy { (application as UHApp).components.fileSizeFormatter }
    override val downloadEstimator: DownloadEstimator by lazy { (application as UHApp).components.downloadEstimator }
    override val packageNameProvider: PackageNameProvider by lazy {
        DefaultPackageNameProvider(applicationContext)
    }
    override val downloadFileUtils: DownloadFileUtils by lazy {
        DefaultDownloadFileUtils(
            context = applicationContext,
            downloadLocation = {
                val custom = app.harvester.companion.SettingsPrefs.getString(applicationContext, "custom_download_uri", "")
                if (custom.isNotBlank()) custom else android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).path
            },
        )
    }
    override val downloadFileWriter: DownloadFileWriter by lazy {
        app.harvester.companion.core.downloads.UhDownloadFileWriter(applicationContext)
    }
}
