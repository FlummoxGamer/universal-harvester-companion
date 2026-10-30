package app.harvester.companion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object DownloadStore {
    enum class Status { PENDING, RUNNING, PAUSED, SUCCESS, FAILED, CANCELLED, WAITING_FOR_NETWORK }

    const val MAX_PARALLEL = 2

    const val MAX_PARALLEL = 2

    data class Task(
        val id: String,
        val url: String,
        val filename: String,
        val mime: String,
        var bytesDownloaded: Long = 0L,
        var totalBytes: Long = -1L,
        var speed: Long = 0L,
        var etaSeconds: Long = -1L,
        var status: Status = Status.PENDING,
        var error: String? = null,
        val base64Data: String? = null,
        val userAgent: String? = null,
        val referer: String? = null,
        val realUrl: String? = null,
        var retryCount: Int = 0,
        var targetUri: String? = null
    )

    private val _activeDownloads = MutableStateFlow<List<Task>>(emptyList())
    val activeDownloads: StateFlow<List<Task>> = _activeDownloads

    fun addTask(task: Task) {
        _activeDownloads.value = _activeDownloads.value + task
    }

    fun updateTask(id: String, updateBlock: (Task) -> Unit) {
        _activeDownloads.value = _activeDownloads.value.map { task ->
            if (task.id == id) {
                val newTask = task.copy()
                updateBlock(newTask)
                newTask
            } else task
        }
    }

    fun removeTask(id: String) {
        _activeDownloads.value = _activeDownloads.value.filterNot { it.id == id }
    }

    fun getTask(id: String): Task? = _activeDownloads.value.find { it.id == id }

    fun getPendingTask(): Task? = _activeDownloads.value.firstOrNull { it.status == Status.PENDING }

    fun getRunningCount(): Int = _activeDownloads.value.count { it.status == Status.RUNNING }
}
