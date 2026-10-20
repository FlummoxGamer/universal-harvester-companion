package app.harvester.companion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object DownloadStore {
    enum class Status { PENDING, RUNNING, PAUSED, SUCCESS, FAILED, CANCELLED }

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
        val referer: String? = null
    )

    private val _activeDownloads = MutableStateFlow<List<Task>>(emptyList())
    val activeDownloads: StateFlow<List<Task>> = _activeDownloads

    // addTask
    fun addTask(task: Task) {
        _activeDownloads.value = _activeDownloads.value + task
    }

    // updateTask
    fun updateTask(id: String, updateBlock: (Task) -> Unit) {
        _activeDownloads.value = _activeDownloads.value.map { task ->
            if (task.id == id) {
                updateBlock(task)
                task
            } else task
        }.toList()
    }

    // removeTask
    fun removeTask(id: String) {
        _activeDownloads.value = _activeDownloads.value.filterNot { it.id == id }
    }

    // getTask
    fun getTask(id: String): Task? = _activeDownloads.value.find { it.id == id }
}
