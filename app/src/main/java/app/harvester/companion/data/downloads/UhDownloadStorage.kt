package app.harvester.companion.data.downloads

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.paging.DataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mozilla.components.browser.state.state.content.DownloadState

class UhDownloadStorage(context: Context) {

    @VisibleForTesting internal var database: Lazy<UhDownloadsDatabase> = lazy { UhDownloadsDatabase.get(context) }
    private val downloadDao by lazy { database.value.downloadDao() }

    suspend fun add(download: DownloadState) {
        downloadDao.insert(download.toUhDownloadEntity())
    }

    fun getDownloads(): Flow<List<DownloadState>> {
        return downloadDao.getDownloads().map { list ->
            list.map { entity -> entity.toDownloadState() }
        }
    }

    suspend fun getDownloadsList(): List<DownloadState> {
        return downloadDao.getDownloadsList().map { entity -> entity.toDownloadState() }
    }

    fun getDownloadsPaged(): DataSource.Factory<Int, DownloadState> =
        downloadDao.getDownloadsPaged().map { entity -> entity.toDownloadState() }

    suspend fun remove(download: DownloadState) {
        downloadDao.delete(download.toUhDownloadEntity())
    }

    suspend fun update(download: DownloadState) {
        downloadDao.update(download.toUhDownloadEntity())
    }

    suspend fun removeAllDownloads() {
        downloadDao.deleteAllDownloads()
    }

    companion object {
        fun isSameDownload(first: DownloadState, second: DownloadState): Boolean {
            return first.id == second.id &&
                first.fileName == second.fileName &&
                first.url == second.url &&
                first.contentType == second.contentType &&
                first.contentLength == second.contentLength &&
                first.status == second.status &&
                first.directoryPath == second.directoryPath &&
                first.createdTime == second.createdTime
        }
    }
}
