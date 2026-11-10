package app.harvester.companion.core.downloads

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.fetch.Client
import mozilla.components.feature.downloads.AbstractFetchDownloadService
import mozilla.components.feature.downloads.DefaultDownloadFileWriter
import mozilla.components.feature.downloads.DefaultFileSizeFormatter
import mozilla.components.feature.downloads.DownloadEstimator
import mozilla.components.feature.downloads.FileSizeFormatter
import mozilla.components.feature.downloads.PackageNameProvider
import mozilla.components.feature.downloads.filewriter.DownloadFileWriter
import mozilla.components.lib.fetch.okhttp.OkHttpClient
import mozilla.components.support.base.android.DefaultNotificationsDelegate
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.utils.DefaultDateTimeProvider
import mozilla.components.support.utils.DefaultDownloadFileUtils
import mozilla.components.support.utils.DownloadFileUtils
import app.harvester.companion.R
import app.harvester.companion.UHApp

class UhDownloadService : AbstractFetchDownloadService() {

    override val store: BrowserStore by lazy { (application as UHApp).components.store }
    
    override val packageNameProvider: PackageNameProvider = object : PackageNameProvider {
        override val packageName: String = applicationContext.packageName
    }

    override val notificationsDelegate: NotificationsDelegate by lazy {
        DefaultNotificationsDelegate(NotificationManagerCompat.from(applicationContext))
    }

    override val httpClient: Client by lazy { OkHttpClient() }

    override val fileSizeFormatter: FileSizeFormatter by lazy { DefaultFileSizeFormatter(applicationContext) }

    override val downloadEstimator: DownloadEstimator by lazy { DownloadEstimator(DefaultDateTimeProvider()) }

    override val downloadFileUtils: DownloadFileUtils by lazy {
        DefaultDownloadFileUtils(applicationContext) {
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).path
        }
    }

    override val downloadFileWriter: DownloadFileWriter by lazy { UhDownloadFileWriter(applicationContext) }

    override val style: Style = Style(notificationAccentColor = R.color.accent)
}
