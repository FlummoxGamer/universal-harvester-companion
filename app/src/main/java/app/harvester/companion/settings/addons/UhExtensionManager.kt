package app.harvester.companion.settings.addons

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import app.harvester.companion.UHLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mozilla.components.browser.state.action.WebExtensionAction
import mozilla.components.browser.state.state.extension.WebExtensionPromptRequest
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.CancellableOperation
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.webextension.EnableSource
import mozilla.components.concept.engine.webextension.InstallationMethod
import mozilla.components.concept.engine.webextension.PermissionPromptResponse
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.concept.engine.webextension.isUnsupported
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object UhExtensionManager {

    private const val PREFS_NAME = "uh_extensions_meta"
    private const val KEY_LAST_UPDATE_CHECK_MS = "last_update_check_ms"
    private const val UPDATE_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
    private const val INSTALL_TIMEOUT_MS = 45_000L

    private val initialized = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var engineRef: Engine? = null

    @Volatile
    private var storeRef: BrowserStore? = null

    private val autoApproveIds = ConcurrentHashMap.newKeySet<String>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeOperations = ConcurrentHashMap<String, CancellableOperation>()

    private val _installedExtensions = MutableStateFlow<List<InstalledExtensionItem>>(emptyList())
    val installedExtensions: StateFlow<List<InstalledExtensionItem>> = _installedExtensions.asStateFlow()

    private val _installedIds = MutableStateFlow<Set<String>>(emptySet())
    val installedIds: StateFlow<Set<String>> = _installedIds.asStateFlow()

    private val _activeTasks = MutableStateFlow<Map<String, ExtensionInstallTask>>(emptyMap())
    val activeTasks: StateFlow<Map<String, ExtensionInstallTask>> = _activeTasks.asStateFlow()

    private val _pendingPrompt = MutableStateFlow<PendingExtensionPrompt?>(null)
    val pendingPrompt: StateFlow<PendingExtensionPrompt?> = _pendingPrompt.asStateFlow()

    private val _availableUpdates = MutableStateFlow<Map<String, ExtensionUpdateInfo>>(emptyMap())
    val availableUpdates: StateFlow<Map<String, ExtensionUpdateInfo>> = _availableUpdates.asStateFlow()

    private val _isCheckingUpdates = MutableStateFlow(false)
    val isCheckingUpdates: StateFlow<Boolean> = _isCheckingUpdates.asStateFlow()

    private val _events = MutableSharedFlow<ExtensionUiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ExtensionUiEvent> = _events.asSharedFlow()

    fun init(context: Context, engine: Engine, store: BrowserStore) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        engineRef = engine
        storeRef = store

        cleanupStagedFiles()
        observeBrowserStore(store)
        refreshInstalledExtensions()
    }

    private fun observeBrowserStore(store: BrowserStore) {
        mainScope.launch {
            store.stateFlow
                .map { it.webExtensionPromptRequest }
                .distinctUntilChanged()
                .collect { request ->
                    if (request != null) {
                        handleStorePromptRequest(request, store)
                    }
                }
        }

        mainScope.launch {
            store.stateFlow
                .map { state ->
                    state.extensions.values.map { Triple(it.id, it.enabled, it.allowedInPrivateBrowsing) }
                }
                .distinctUntilChanged()
                .collect {
                    refreshInstalledExtensions()
                }
        }
    }

    private fun handleStorePromptRequest(request: WebExtensionPromptRequest, store: BrowserStore) {
        when (request) {
            is WebExtensionPromptRequest.AfterInstallation.Permissions.Required -> {
                val ext = request.extension
                val isAutoApproved = autoApproveIds.contains(ext.id) ||
                    autoApproveIds.contains("*")

                if (isAutoApproved) {
                    UHLog.d("ext", "Auto-approving required permissions for ${ext.id}")
                    request.onConfirm(
                        PermissionPromptResponse(
                            isPermissionsGranted = true,
                            isPrivateModeGranted = true,
                            isTechnicalAndInteractionDataGranted = false,
                        )
                    )
                    store.dispatch(WebExtensionAction.ConsumePromptRequestWebExtensionAction)
                    return
                }

                updateTaskStageMatching(ext.id, ext.getMetadata()?.name, InstallStage.AWAITING_APPROVAL)

                val prompt = PendingExtensionPrompt.Install(
                    extensionRef = ext,
                    permissions = request.permissions,
                    origins = request.origins,
                    dataCollectionPermissions = request.dataCollectionPermissions,
                    onResult = { granted, allowPrivate, allowData ->
                        if (_pendingPrompt.value?.extensionId == ext.id) {
                            _pendingPrompt.value = null
                        }
                        store.dispatch(WebExtensionAction.ConsumePromptRequestWebExtensionAction)
                        if (granted) {
                            updateTaskStageMatching(ext.id, ext.getMetadata()?.name, InstallStage.INSTALLING)
                        } else {
                            clearTasksMatching(ext.id, ext.getMetadata()?.name)
                        }
                        request.onConfirm(
                            PermissionPromptResponse(
                                isPermissionsGranted = granted,
                                isPrivateModeGranted = allowPrivate,
                                isTechnicalAndInteractionDataGranted = allowData,
                            )
                        )
                    }
                )

                // If another stale prompt existed, deny it cleanly first so Gecko never stalls.
                (_pendingPrompt.value as? PendingExtensionPrompt.Install)?.respond(granted = false)
                (_pendingPrompt.value as? PendingExtensionPrompt.Update)?.respond(granted = false)
                (_pendingPrompt.value as? PendingExtensionPrompt.Optional)?.respond(granted = false)
                _pendingPrompt.value = prompt
            }

            is WebExtensionPromptRequest.AfterInstallation.Permissions.Optional -> {
                val ext = request.extension
                if (autoApproveIds.contains(ext.id)) {
                    request.onConfirm(true)
                    store.dispatch(WebExtensionAction.ConsumePromptRequestWebExtensionAction)
                    return
                }
                val prompt = PendingExtensionPrompt.Optional(
                    extensionRef = ext,
                    permissions = request.permissions,
                    origins = request.origins,
                    dataCollectionPermissions = request.dataCollectionPermissions,
                    onResult = { granted ->
                        if (_pendingPrompt.value?.extensionId == ext.id) {
                            _pendingPrompt.value = null
                        }
                        store.dispatch(WebExtensionAction.ConsumePromptRequestWebExtensionAction)
                        request.onConfirm(granted)
                    }
                )
                _pendingPrompt.value = prompt
            }

            is WebExtensionPromptRequest.AfterInstallation.PostInstallation -> {
                store.dispatch(WebExtensionAction.ConsumePromptRequestWebExtensionAction)
                refreshInstalledExtensions()
            }

            is WebExtensionPromptRequest.BeforeInstallation.InstallationFailed -> {
                store.dispatch(WebExtensionAction.ConsumePromptRequestWebExtensionAction)
                val extName = request.extension?.getMetadata()?.name ?: "Extension"
                UHLog.w("ext", "Extension install failed ($extName): ${request.exception.message}")
            }

            is WebExtensionPromptRequest.InstallationRequested -> {
                store.dispatch(WebExtensionAction.ConsumePromptRequestWebExtensionAction)
                val name = request.name?.ifBlank { "Extension" } ?: "Extension"
                installFromUrl(url = request.url, key = request.url, name = name)
            }
        }
    }

    fun handleUpdatePermissionRequest(
        extension: WebExtension,
        newPermissions: List<String>,
        newOrigins: List<String>,
        newDataCollectionPermissions: List<String>,
        onPermissionsGranted: (Boolean) -> Unit,
    ) {
        if (autoApproveIds.contains(extension.id)) {
            onPermissionsGranted(true)
            return
        }
        val prompt = PendingExtensionPrompt.Update(
            extensionRef = extension,
            permissions = newPermissions,
            origins = newOrigins,
            dataCollectionPermissions = newDataCollectionPermissions,
            onResult = { granted ->
                if (_pendingPrompt.value?.extensionId == extension.id) {
                    _pendingPrompt.value = null
                }
                onPermissionsGranted(granted)
            }
        )
        _pendingPrompt.value = prompt
    }

    fun refreshInstalledExtensions(onComplete: ((List<InstalledExtensionItem>) -> Unit)? = null) {
        val engine = engineRef ?: return
        mainHandler.post {
            engine.listInstalledWebExtensions(
                onSuccess = { list ->
                    val updatesMap = _availableUpdates.value
                    val items = list
                        .filter { it.id !in BUILTIN_INTERNAL_EXTENSION_IDS && !it.isBuiltIn() }
                        .map { ext ->
                            val meta = ext.getMetadata()
                            val extId = ext.id
                            val update = updatesMap[extId]?.takeIf {
                                isNewerVersion(it.latestVersion, meta?.version.orEmpty())
                            }
                            InstalledExtensionItem(
                                id = extId,
                                name = meta?.name?.ifBlank { extId } ?: extId,
                                version = meta?.version.orEmpty(),
                                description = meta?.description.orEmpty(),
                                creatorName = meta?.creatorName?.ifBlank { meta.developerName.orEmpty() }
                                    ?: meta?.developerName.orEmpty(),
                                homepageUrl = meta?.homepageUrl.orEmpty(),
                                optionsPageUrl = meta?.optionsPageUrl,
                                openOptionsInTab = meta?.openOptionsPageInTab ?: true,
                                enabled = ext.isEnabled(),
                                allowedInPrivateBrowsing = ext.isAllowedInPrivateBrowsing(),
                                requiredPermissions = meta?.requiredPermissions.orEmpty(),
                                requiredOrigins = meta?.requiredOrigins.orEmpty(),
                                optionalPermissions = meta?.optionalPermissions.orEmpty(),
                                grantedOptionalPermissions = meta?.grantedOptionalPermissions.orEmpty(),
                                optionalOrigins = meta?.optionalOrigins.orEmpty(),
                                grantedOptionalOrigins = meta?.grantedOptionalOrigins.orEmpty(),
                                isUnsupported = ext.isUnsupported(),
                                updateInfo = update,
                                extensionRef = ext,
                            )
                        }
                        .sortedWith(
                            compareByDescending<InstalledExtensionItem> { it.enabled }
                                .thenBy { it.name.lowercase() }
                        )

                    _installedExtensions.value = items
                    _installedIds.value = list.map { it.id }.toSet()

                    // Prune updates that have already been satisfied
                    val prunedUpdates = updatesMap.filter { (id, info) ->
                        val installed = items.firstOrNull { it.id == id }
                        installed != null && isNewerVersion(info.latestVersion, installed.version)
                    }
                    if (prunedUpdates.size != updatesMap.size) {
                        _availableUpdates.value = prunedUpdates
                    }

                    onComplete?.invoke(items)
                },
                onError = { err ->
                    UHLog.w("ext", "Failed listing installed extensions: ${err.message}")
                    onComplete?.invoke(_installedExtensions.value)
                }
            )
        }
    }

    fun installFromUrl(
        url: String,
        key: String,
        name: String,
        autoApprove: Boolean = false,
        onFinished: ((Result<WebExtension>) -> Unit)? = null,
    ) {
        val engine = engineRef
        if (engine == null) {
            emitEvent("Extension engine is still starting. Please try again.", isError = true)
            onFinished?.invoke(Result.failure(IllegalStateException("Engine not initialized")))
            return
        }
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            emitEvent("Invalid extension URL", isError = true)
            onFinished?.invoke(Result.failure(IllegalArgumentException("Blank URL")))
            return
        }
        if (activeJobs.containsKey(key)) return

        val displayName = name.ifBlank { "Extension" }
        _activeTasks.update { it + (key to ExtensionInstallTask(key, displayName, InstallStage.DOWNLOADING)) }

        val job = ioScope.launch {
            if (autoApprove) autoApproveIds.add(key)
            try {
                val method = if (trimmed.startsWith("file://")) {
                    InstallationMethod.FROM_FILE
                } else {
                    InstallationMethod.MANAGER
                }
                val installed = executeEngineInstallWithTimeout(
                    engine = engine,
                    sourceUrl = trimmed,
                    key = key,
                    method = method,
                )
                refreshInstalledExtensions()
                val finalName = installed.getMetadata()?.name?.ifBlank { displayName } ?: displayName
                emitEvent("$finalName installed")
                onFinished?.invoke(Result.success(installed))
            } catch (e: CancellationException) {
                emitEvent("Cancelled $displayName installation")
                onFinished?.invoke(Result.failure(e))
            } catch (e: Exception) {
                val msg = friendlyErrorMessage(e)
                UHLog.w("ext", "Install failed for $displayName ($trimmed): ${e.message}")
                emitEvent(msg, isError = true)
                onFinished?.invoke(Result.failure(e))
            } finally {
                if (autoApprove) autoApproveIds.remove(key)
                activeJobs.remove(key)
                activeOperations.remove(key)
                _activeTasks.update { it - key }
            }
        }
        activeJobs[key] = job
    }

    suspend fun installAutoApprovedAwait(
        extensionId: String,
        xpiUrl: String,
        displayName: String,
    ): WebExtension {
        val engine = engineRef ?: throw IllegalStateException("Extension engine not ready")
        autoApproveIds.add(extensionId)
        try {
            val result = executeEngineInstallWithTimeout(
                engine = engine,
                sourceUrl = xpiUrl,
                key = extensionId,
                method = if (xpiUrl.startsWith("file://")) InstallationMethod.FROM_FILE else InstallationMethod.MANAGER,
            )
            refreshInstalledExtensions()
            return result
        } finally {
            autoApproveIds.remove(extensionId)
            activeOperations.remove(extensionId)
        }
    }

    fun installFromLocalUri(context: Context, uri: Uri, displayName: String = "Local extension") {
        val engine = engineRef
        if (engine == null) {
            emitEvent("Extension engine is still starting. Please try again.", isError = true)
            return
        }
        val key = "local:${uri}"
        if (activeJobs.containsKey(key)) return

        _activeTasks.update { it + (key to ExtensionInstallTask(key, displayName, InstallStage.VERIFYING)) }

        val job = ioScope.launch {
            var stagedFile: File? = null
            try {
                val stageDir = File(context.applicationContext.cacheDir, "ext_stage").apply { mkdirs() }
                val target = File(stageDir, "sideload_${System.currentTimeMillis()}.xpi")
                stagedFile = target

                context.applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Could not read selected file")

                if (!isValidZipArchive(target)) {
                    throw IllegalArgumentException("Selected file is not a valid .xpi extension package")
                }

                _activeTasks.update { current ->
                    val existing = current[key] ?: return@update current
                    current + (key to existing.copy(stage = InstallStage.INSTALLING))
                }

                val fileUri = Uri.fromFile(target).toString()
                val installed = executeEngineInstallWithTimeout(
                    engine = engine,
                    sourceUrl = fileUri,
                    key = key,
                    method = InstallationMethod.FROM_FILE,
                )
                refreshInstalledExtensions()
                val finalName = installed.getMetadata()?.name?.ifBlank { displayName } ?: displayName
                emitEvent("$finalName installed")
            } catch (e: CancellationException) {
                emitEvent("Cancelled installation")
            } catch (e: Exception) {
                val msg = friendlyErrorMessage(e)
                UHLog.w("ext", "Local .xpi install failed ($uri): ${e.message}")
                emitEvent(msg, isError = true)
            } finally {
                stagedFile?.delete()
                activeJobs.remove(key)
                activeOperations.remove(key)
                _activeTasks.update { it - key }
            }
        }
        activeJobs[key] = job
    }

    fun cancelInstall(key: String) {
        activeOperations.remove(key)?.cancel()
        activeJobs.remove(key)?.cancel()
        val prompt = _pendingPrompt.value
        if (prompt != null && (prompt.extensionId == key || _activeTasks.value[key]?.name == prompt.extensionName)) {
            denyPrompt(prompt)
        }
        _activeTasks.update { it - key }
    }

    fun cancelActiveOperation(key: String) {
        activeOperations.remove(key)?.cancel()
    }

    fun denyPrompt(prompt: PendingExtensionPrompt) {
        when (prompt) {
            is PendingExtensionPrompt.Install -> prompt.respond(granted = false)
            is PendingExtensionPrompt.Update -> prompt.respond(granted = false)
            is PendingExtensionPrompt.Optional -> prompt.respond(granted = false)
        }
    }

    fun setEnabled(item: InstalledExtensionItem, enabled: Boolean) {
        val engine = engineRef ?: return
        mainHandler.post {
            if (enabled) {
                engine.enableWebExtension(
                    extension = item.extensionRef,
                    source = EnableSource.USER,
                    onSuccess = {
                        refreshInstalledExtensions()
                    },
                    onError = {
                        emitEvent("Could not enable ${item.name}", isError = true)
                    }
                )
            } else {
                engine.disableWebExtension(
                    extension = item.extensionRef,
                    source = EnableSource.USER,
                    onSuccess = {
                        refreshInstalledExtensions()
                    },
                    onError = {
                        emitEvent("Could not disable ${item.name}", isError = true)
                    }
                )
            }
        }
    }

    fun setAllowedInPrivateBrowsing(item: InstalledExtensionItem, allowed: Boolean) {
        val engine = engineRef ?: return
        mainHandler.post {
            engine.setAllowedInPrivateBrowsing(
                extension = item.extensionRef,
                allowed = allowed,
                onSuccess = {
                    refreshInstalledExtensions()
                    emitEvent(
                        if (allowed) "${item.name} allowed in Private Browsing"
                        else "${item.name} disabled in Private Browsing"
                    )
                },
                onError = {
                    emitEvent("Could not update private browsing setting", isError = true)
                }
            )
        }
    }

    fun uninstall(item: InstalledExtensionItem) {
        val engine = engineRef ?: return
        mainHandler.post {
            engine.uninstallWebExtension(
                ext = item.extensionRef,
                onSuccess = {
                    _availableUpdates.update { it - item.id }
                    refreshInstalledExtensions()
                    emitEvent("${item.name} uninstalled")
                },
                onError = { _, err ->
                    UHLog.w("ext", "Uninstall failed for ${item.id}: ${err.message}")
                    emitEvent("Failed to uninstall ${item.name}", isError = true)
                }
            )
        }
    }

    fun checkForUpdates(force: Boolean = false) {
        val ctx = appContext ?: return
        if (_isCheckingUpdates.value) return

        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(KEY_LAST_UPDATE_CHECK_MS, 0L)
        if (!force && (System.currentTimeMillis() - lastCheck < UPDATE_CHECK_INTERVAL_MS)) {
            return
        }

        _isCheckingUpdates.value = true
        ioScope.launch {
            try {
                val installed = _installedExtensions.value
                val foundUpdates = linkedMapOf<String, ExtensionUpdateInfo>()
                for (item in installed) {
                    val latest = AmoApiClient.fetchAddon(item.id) ?: continue
                    if (latest.xpiUrl.isNotBlank() && isNewerVersion(latest.version, item.version)) {
                        foundUpdates[item.id] = ExtensionUpdateInfo(
                            extensionId = item.id,
                            extensionName = item.name,
                            currentVersion = item.version,
                            latestVersion = latest.version,
                            xpiUrl = latest.xpiUrl,
                        )
                    }
                }
                prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_MS, System.currentTimeMillis()).apply()
                _availableUpdates.value = foundUpdates
                refreshInstalledExtensions()
                if (force) {
                    val msg = if (foundUpdates.isEmpty()) {
                        "All extensions are up to date"
                    } else {
                        "${foundUpdates.size} update${if (foundUpdates.size == 1) "" else "s"} available"
                    }
                    emitEvent(msg)
                }
            } catch (e: Exception) {
                UHLog.w("ext", "Update check failed: ${e.message}")
                if (force) {
                    emitEvent("Could not check for updates", isError = true)
                }
            } finally {
                _isCheckingUpdates.value = false
            }
        }
    }

    fun updateExtension(extensionId: String) {
        val info = _availableUpdates.value[extensionId] ?: return
        installFromUrl(
            url = info.xpiUrl,
            key = info.extensionId,
            name = info.extensionName,
            onFinished = { res ->
                if (res.isSuccess) {
                    _availableUpdates.update { it - extensionId }
                }
            }
        )
    }

    fun updateAllExtensions() {
        val pending = _availableUpdates.value.values.toList()
        if (pending.isEmpty()) return
        ioScope.launch {
            for (info in pending) {
                val done = CompletableDeferred<Unit>()
                installFromUrl(
                    url = info.xpiUrl,
                    key = info.extensionId,
                    name = info.extensionName,
                    onFinished = { res ->
                        if (res.isSuccess) {
                            _availableUpdates.update { it - info.extensionId }
                        }
                        done.complete(Unit)
                    }
                )
                done.await()
            }
        }
    }

    private suspend fun executeEngineInstallWithTimeout(
        engine: Engine,
        sourceUrl: String,
        key: String,
        method: InstallationMethod,
    ): WebExtension {
        val resultDeferred = CompletableDeferred<WebExtension>()

        withContext(Dispatchers.Main) {
            val cancellable = engine.installWebExtension(
                url = sourceUrl,
                installationMethod = method,
                onSuccess = { ext ->
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.complete(ext)
                    }
                },
                onError = { err ->
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.completeExceptionally(err)
                    }
                }
            )
            activeOperations[key] = cancellable
        }

        // Watchdog timer pauses while a user permission prompt is actively open so users have time to read permissions.
        val watchdog = ioScope.launch {
            var activeElapsedMs = 0L
            val stepMs = 500L
            while (!resultDeferred.isCompleted) {
                delay(stepMs)
                val awaitingUserPrompt = _pendingPrompt.value != null
                if (!awaitingUserPrompt) {
                    activeElapsedMs += stepMs
                    if (activeElapsedMs >= INSTALL_TIMEOUT_MS) {
                        activeOperations.remove(key)?.cancel()
                        if (!resultDeferred.isCompleted) {
                            resultDeferred.completeExceptionally(
                                IllegalStateException("Installation timed out. Please check your connection and retry.")
                            )
                        }
                        break
                    }
                }
            }
        }

        return try {
            resultDeferred.await()
        } catch (e: CancellationException) {
            activeOperations.remove(key)?.cancel()
            throw e
        } finally {
            watchdog.cancel()
        }
    }

    private fun updateTaskStageMatching(extensionId: String, extensionName: String?, stage: InstallStage) {
        _activeTasks.update { map ->
            if (map.isEmpty()) return@update map
            var changed = false
            val updated = map.mapValues { (taskKey, task) ->
                val matches = taskKey == extensionId ||
                    (!extensionName.isNullOrBlank() && task.name.equals(extensionName, ignoreCase = true)) ||
                    map.size == 1
                if (matches && task.stage != stage) {
                    changed = true
                    task.copy(stage = stage)
                } else {
                    task
                }
            }
            if (changed) updated else map
        }
    }

    private fun clearTasksMatching(extensionId: String, extensionName: String?) {
        _activeTasks.update { map ->
            map.filterNot { (taskKey, task) ->
                taskKey == extensionId ||
                    (!extensionName.isNullOrBlank() && task.name.equals(extensionName, ignoreCase = true))
            }
        }
    }

    private fun isValidZipArchive(file: File): Boolean {
        if (!file.isFile || file.length() < 64L) return false
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(2)
                val read = input.read(header)
                read == 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun cleanupStagedFiles() {
        ioScope.launch {
            try {
                val dir = File(appContext?.cacheDir ?: return@launch, "ext_stage")
                if (dir.isDirectory) {
                    dir.listFiles()?.forEach { it.delete() }
                }
            } catch (_: Exception) {}
        }
    }

    private fun emitEvent(message: String, isError: Boolean = false) {
        _events.tryEmit(ExtensionUiEvent(message = message, isError = isError))
    }

    private fun friendlyErrorMessage(e: Throwable): String {
        val raw = e.message.orEmpty()
        return when {
            raw.contains("USER_CANCELLED", ignoreCase = true) ||
                raw.contains("canceled", ignoreCase = true) ||
                raw.contains("cancelled", ignoreCase = true) -> "Installation cancelled"
            raw.contains("CORRUPT", ignoreCase = true) ||
                raw.contains("not a valid .xpi", ignoreCase = true) -> "Package is invalid or corrupted"
            raw.contains("INCOMPATIBLE", ignoreCase = true) -> "Extension is incompatible with this browser version"
            raw.contains("BLOCKLISTED", ignoreCase = true) -> "Extension is blocked for security reasons"
            raw.contains("NETWORK", ignoreCase = true) ||
                raw.contains("UnknownHost", ignoreCase = true) -> "Network error while downloading extension"
            raw.contains("timed out", ignoreCase = true) -> "Installation timed out. Please retry."
            raw.isNotBlank() -> "Install failed: $raw"
            else -> "Could not install extension"
        }
    }

    fun isNewerVersion(candidate: String, current: String): Boolean {
        if (candidate.isBlank() || candidate == current) return false
        if (current.isBlank()) return true
        val cParts = candidate.split(".", "-", "_")
        val curParts = current.split(".", "-", "_")
        val maxLen = maxOf(cParts.size, curParts.size)
        for (i in 0 until maxLen) {
            val a = cParts.getOrNull(i) ?: "0"
            val b = curParts.getOrNull(i) ?: "0"
            val aNum = a.toLongOrNull()
            val bNum = b.toLongOrNull()
            val cmp = if (aNum != null && bNum != null) aNum.compareTo(bNum) else a.compareTo(b)
            if (cmp != 0) return cmp > 0
        }
        return false
    }
}
