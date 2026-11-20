package app.harvester.companion.core.downloads

import android.app.DownloadManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import app.harvester.companion.R
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.DownloadAction
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.MutableHeaders
import mozilla.components.concept.fetch.Request
import mozilla.components.feature.downloads.AbstractFetchDownloadService
import mozilla.components.feature.downloads.DefaultPackageNameProvider
import mozilla.components.feature.downloads.DownloadEstimator
import mozilla.components.feature.downloads.FileSizeFormatter
import mozilla.components.feature.downloads.PackageNameProvider
import mozilla.components.feature.downloads.filewriter.DownloadFileWriter
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.utils.DefaultDateTimeProvider
import mozilla.components.support.utils.DefaultDownloadFileUtils
import mozilla.components.support.utils.DownloadFileUtils
import java.util.concurrent.ConcurrentHashMap

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
                DownloadLocationResolver.resolveTargetDirectory(applicationContext)
            },
        )
    }
    override val downloadFileWriter: DownloadFileWriter by lazy {
        UhDownloadFileWriter(applicationContext)
    }

    private val resumeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeRangeJobs = ConcurrentHashMap<String, Job>()
    private val uhFormatter by lazy { UhDefaultFileSizeFormatter(applicationContext) }
    private val uhEstimator by lazy { UhDownloadEstimator(DefaultDateTimeProvider()) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val downloadId = intent?.getStringExtra("downloadId")
            ?: intent?.getStringExtra(DownloadManager.EXTRA_DOWNLOAD_ID)
            ?: intent?.getStringExtra("INTENT_EXTRA_DOWNLOAD_ID")

        val action = intent?.action
        if (!downloadId.isNullOrBlank()) {
            intent?.putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, downloadId)
            val download = store.state.downloads[downloadId]

            when (action) {
                ACTION_PAUSE -> {
                    activeRangeJobs.remove(downloadId)?.cancel()
                    if (download != null) {
                        val pausedState = download.copy(status = DownloadState.Status.PAUSED)
                        store.dispatch(DownloadAction.UpdateDownloadAction(pausedState))
                        postNotification(pausedState, isForeground = false)
                    }
                }
                ACTION_CANCEL -> {
                    activeRangeJobs.remove(downloadId)?.cancel()
                }
                ACTION_RESUME, ACTION_TRY_AGAIN -> {
                    if (download != null) {
                        if (activeRangeJobs[downloadId]?.isActive == true) {
                            return START_STICKY
                        }
                        if (download.currentBytesCopied > 0L || action == ACTION_RESUME) {
                            val ongoingPreview = download.copy(status = DownloadState.Status.DOWNLOADING)
                            postNotification(ongoingPreview, isForeground = true)
                            resumeDownloadWithRange(downloadId)
                            return START_STICKY
                        }
                    }
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun notificationIdFor(download: DownloadState): Int {
        val id = download.notificationId ?: download.id.hashCode()
        return if (id == 0 || id == UhDownloadNotification.NOTIFICATION_DOWNLOAD_GROUP_ID) {
            (download.id.hashCode() and 0x7fffffff).coerceAtLeast(1000)
        } else {
            id
        }
    }

    private fun postNotification(download: DownloadState, isForeground: Boolean) {
        val notifId = notificationIdFor(download)
        val notification = when (download.status) {
            DownloadState.Status.DOWNLOADING, DownloadState.Status.INITIATED -> {
                UhDownloadNotification.createOngoingDownloadNotification(
                    context = applicationContext,
                    downloadState = download,
                    fileSizeFormatter = uhFormatter,
                    notificationAccentColor = R.color.accent,
                    downloadEstimator = uhEstimator,
                )
            }
            DownloadState.Status.PAUSED -> {
                UhDownloadNotification.createPausedDownloadNotification(
                    context = applicationContext,
                    downloadState = download,
                    createdTime = download.createdTime,
                    notificationAccentColor = R.color.accent,
                )
            }
            DownloadState.Status.COMPLETED -> {
                UhDownloadNotification.createDownloadCompletedNotification(
                    context = applicationContext,
                    downloadState = download,
                    createdTime = download.createdTime,
                    notificationAccentColor = R.color.accent,
                    downloadFileUtils = downloadFileUtils,
                    contentIntent = null,
                )
            }
            DownloadState.Status.FAILED -> {
                UhDownloadNotification.createDownloadFailedNotification(
                    context = applicationContext,
                    downloadState = download,
                    createdTime = download.createdTime,
                    notificationAccentColor = R.color.accent,
                )
            }
            DownloadState.Status.CANCELLED -> return
        }

        if (isForeground) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        notifId,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(notifId, notification)
                }
            } catch (_: Exception) {
                notificationsDelegate.notify(notificationId = notifId, notification = notification)
            }
        } else {
            notificationsDelegate.notify(notificationId = notifId, notification = notification)
        }
    }

    private fun resumeDownloadWithRange(downloadId: String) {
        activeRangeJobs[downloadId]?.cancel()
        val job = resumeScope.launch {
            val initial = store.state.downloads[downloadId] ?: return@launch
            val components = (application as UHApp).components
            val offset = initial.currentBytesCopied
            var latestState = initial.copy(status = DownloadState.Status.DOWNLOADING)
            store.dispatch(DownloadAction.UpdateDownloadAction(latestState))

            try {
                val headers = MutableHeaders()
                if (offset > 0L) {
                    headers.set("Range", "bytes=$offset-")
                }
                val response = httpClient.fetch(
                    Request(
                        url = initial.url,
                        method = Request.Method.GET,
                        headers = headers,
                    )
                )
                val isPartial = response.status == 206
                val append = offset > 0L && isPartial
                var totalCopied = if (append) offset else 0L
                val contentRangeLen = response.headers["Content-Length"]?.toLongOrNull()
                val totalLength = initial.contentLength ?: contentRangeLen?.let { if (append) offset + it else it }
                latestState = latestState.copy(
                    currentBytesCopied = totalCopied,
                    contentLength = totalLength,
                    status = DownloadState.Status.DOWNLOADING
                )
                store.dispatch(DownloadAction.UpdateDownloadAction(latestState))
                postNotification(latestState, isForeground = true)

                val shouldScoped = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    !latestState.directoryPath.startsWith("content://")
                downloadFileWriter.useFileStream(
                    download = latestState,
                    append = append,
                    shouldUseScopedStorage = shouldScoped,
                    onUpdateState = { updated ->
                        latestState = updated
                        store.dispatch(DownloadAction.UpdateDownloadAction(updated))
                    },
                ) { outStream ->
                    response.body.useStream { input ->
                        val buffer = ByteArray(16 * 1024)
                        var lastUiUpdate = 0L
                        while (true) {
                            val currentStatus = store.state.downloads[downloadId]?.status
                            if (currentStatus == DownloadState.Status.PAUSED || currentStatus == DownloadState.Status.CANCELLED) {
                                return@useStream
                            }
                            if (!components.isNetworkAvailable()) {
                                components.markWaitingForNetwork(downloadId)
                                latestState = latestState.copy(
                                    currentBytesCopied = totalCopied,
                                    status = DownloadState.Status.PAUSED
                                )
                                store.dispatch(DownloadAction.UpdateDownloadAction(latestState))
                                postNotification(latestState, isForeground = false)
                                return@useStream
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            outStream.write(buffer, 0, read)
                            totalCopied += read
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate >= 300L) {
                                lastUiUpdate = now
                                latestState = latestState.copy(
                                    currentBytesCopied = totalCopied,
                                    contentLength = totalLength,
                                    status = DownloadState.Status.DOWNLOADING
                                )
                                store.dispatch(DownloadAction.UpdateDownloadAction(latestState))
                                postNotification(latestState, isForeground = false)
                            }
                        }
                    }
                }

                val currentAfter = store.state.downloads[downloadId] ?: latestState
                if (currentAfter.status == DownloadState.Status.DOWNLOADING) {
                    val finalTotal = totalLength ?: totalCopied
                    val completed = currentAfter.copy(
                        currentBytesCopied = totalCopied,
                        contentLength = finalTotal,
                        status = DownloadState.Status.COMPLETED
                    )
                    store.dispatch(DownloadAction.UpdateDownloadAction(completed))
                    components.clearWaitingForNetwork(downloadId)
                    try {
                        stopForeground(STOP_FOREGROUND_DETACH)
                    } catch (_: Exception) {}
                    postNotification(completed, isForeground = false)
                }
            } catch (_: CancellationException) {
                val latest = store.state.downloads[downloadId] ?: latestState
                if (latest.status == DownloadState.Status.DOWNLOADING) {
                    val paused = latest.copy(status = DownloadState.Status.PAUSED)
                    store.dispatch(DownloadAction.UpdateDownloadAction(paused))
                    postNotification(paused, isForeground = false)
                }
            } catch (e: Exception) {
                UHLog.w("download_warn", "Range resume interrupted for $downloadId: ${e.message}")
                val latest = store.state.downloads[downloadId] ?: latestState
                try {
                    stopForeground(STOP_FOREGROUND_DETACH)
                } catch (_: Exception) {}
                if (!components.isNetworkAvailable()) {
                    components.markWaitingForNetwork(downloadId)
                    val waiting = latest.copy(status = DownloadState.Status.PAUSED)
                    store.dispatch(DownloadAction.UpdateDownloadAction(waiting))
                    postNotification(waiting, isForeground = false)
                } else if (latest.status != DownloadState.Status.PAUSED && latest.status != DownloadState.Status.CANCELLED) {
                    val failed = latest.copy(status = DownloadState.Status.FAILED)
                    store.dispatch(DownloadAction.UpdateDownloadAction(failed))
                    postNotification(failed, isForeground = false)
                }
            } finally {
                activeRangeJobs.remove(downloadId)
            }
        }
        activeRangeJobs[downloadId] = job
    }
}
