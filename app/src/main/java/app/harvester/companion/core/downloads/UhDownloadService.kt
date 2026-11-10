package app.harvester.companion.core.downloads

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import app.harvester.companion.R
import app.harvester.companion.UHApp
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.fetch.Client
import mozilla.components.feature.downloads.AbstractFetchDownloadService
import mozilla.components.feature.downloads.FileSizeFormatter
import mozilla.components.feature.downloads.PackageNameProvider
import mozilla.components.feature.downloads.filewriter.DownloadFileWriter
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.base.android.PowerManagerInfoProvider
import mozilla.components.support.utils.DefaultDateTimeProvider
import mozilla.components.support.utils.DefaultDownloadFileUtils
import mozilla.components.support.utils.DownloadFileUtils

class UhDownloadService : AbstractFetchDownloadService() {

    override val store: BrowserStore by lazy { (application as UHApp).components.store }
    
    override val packageNameProvider: PackageNameProvider = object : PackageNameProvider {
        override val packageName: String = applicationContext.packageName
    }

    override val notificationsDelegate: NotificationsDelegate by lazy {
        object : NotificationsDelegate {
            override fun notify(notificationId: Int, notification: android.app.Notification) {
                NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
            }
        }
    }

    override val httpClient: Client by lazy { (application as UHApp).components.client }

    override val fileSizeFormatter: FileSizeFormatter = object : FileSizeFormatter {
        private val formatter = UhDefaultFileSizeFormatter(applicationContext)
        override fun formatSizeInBytes(sizeInBytes: Long) = formatter.formatSizeInBytes(sizeInBytes)
    }

    override val downloadEstimator: UhDownloadEstimator by lazy { UhDownloadEstimator(DefaultDateTimeProvider()) }

    override val downloadFileUtils: DownloadFileUtils by lazy {
        DefaultDownloadFileUtils(applicationContext) {
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).path
        }
    }

    override val downloadFileWriter: DownloadFileWriter by lazy { UhDownloadFileWriter(applicationContext) }

    override val style: Style = Style(notificationAccentColor = R.color.accent)
}
