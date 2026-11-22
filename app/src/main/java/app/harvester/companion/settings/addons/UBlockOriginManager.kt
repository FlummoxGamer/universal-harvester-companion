package app.harvester.companion.settings.addons

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.UHLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.webextension.EnableSource
import mozilla.components.concept.engine.webextension.WebExtension
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

object UBlockOriginManager {
    const val UBLOCK_ID = "uBlock0@raymondhill.net"
    private const val UBLOCK_SLUG = "ublock-origin"
    private const val PREFS_NAME = "uh_ublock"
    private const val KEY_PAUSED_HOSTS = "paused_hosts"
    private const val KEY_LAST_CHECK = "last_update_check_ms"
    private const val KEY_INSTALLED_VER = "installed_version"
    private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
    private const val UPDATE_TIMEOUT_MS = 50_000L

    data class State(
        val isInstalled: Boolean = false,
        val isEnabled: Boolean = true,
        val isUpdating: Boolean = false,
        val version: String = "",
        val optionsPageUrl: String? = null,
        val lastError: String? = null,
        val pausedHosts: Set<String> = emptySet(),
        val totalBlockedCount: Int = 0,
    )

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var engineRef: Engine? = null

    @Volatile
    private var installedExt: WebExtension? = null

    @Volatile
    private var activeUpdateJob: Job? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun init(ctx: Context, engine: Engine) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = ctx.applicationContext
        engineRef = engine

        val enabled = SettingsPrefs.getBool(ctx, SettingsPrefs.K_UBLOCK_ENABLED, true)
        val paused = readPausedHosts(ctx)
        val cachedVer = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_INSTALLED_VER, "") ?: ""
        _state.value = _state.value.copy(
            isEnabled = enabled,
            pausedHosts = paused,
            version = cachedVer,
            lastError = null,
        )

        syncInstalledState { isPresent ->
            val autoUpdate = SettingsPrefs.getBool(ctx, SettingsPrefs.K_UBLOCK_AUTO_UPDATE, true)
            val lastCheck = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0L)
            val dueForUpdate = autoUpdate && (System.currentTimeMillis() - lastCheck > CHECK_INTERVAL_MS)
            // Never force-download uBlock Origin; only update if user manually installed it
            if (isPresent && enabled && dueForUpdate) {
                checkAndInstallOrUpdate(force = false)
            }
        }
    }

    fun isSitePaused(url: String): Boolean {
        val host = extractHost(url) ?: return false
        return host in _state.value.pausedHosts
    }

    fun toggleSitePaused(url: String): Boolean {
        val host = extractHost(url) ?: return false
        val ctx = appContext ?: return false
        val next = _state.value.pausedHosts.toMutableSet()
        val nowPaused = if (host in next) {
            next.remove(host)
            false
        } else {
            next.add(host)
            true
        }
        savePausedHosts(ctx, next)
        _state.update { it.copy(pausedHosts = next) }
        return nowPaused
    }

    fun setGloballyEnabled(enabled: Boolean) {
        val ctx = appContext ?: return
        val engine = engineRef ?: return
        SettingsPrefs.setBool(ctx, SettingsPrefs.K_UBLOCK_ENABLED, enabled)
        _state.update { it.copy(isEnabled = enabled, lastError = null) }

        mainHandler.post {
            val ext = installedExt
            if (ext != null) {
                if (enabled) {
                    engine.enableWebExtension(
                        extension = ext,
                        source = EnableSource.USER,
                        onSuccess = { updated ->
                            installedExt = updated
                            _state.update { it.copy(isInstalled = true, isEnabled = true, lastError = null) }
                            UhExtensionManager.refreshInstalledExtensions()
                        },
                        onError = { err ->
                            UHLog.w("ublock", "Failed enabling uBlock Origin: ${err.message}")
                        }
                    )
                } else {
                    engine.disableWebExtension(
                        extension = ext,
                        source = EnableSource.USER,
                        onSuccess = { updated ->
                            installedExt = updated
                            _state.update { it.copy(isEnabled = false) }
                            UhExtensionManager.refreshInstalledExtensions()
                        },
                        onError = { err ->
                            UHLog.w("ublock", "Failed disabling uBlock Origin: ${err.message}")
                        }
                    )
                }
            } else if (enabled) {
                checkAndInstallOrUpdate(force = true)
            }
        }
    }

    fun recordBlocked(count: Int = 1) {
        if (count <= 0) return
        _state.update { it.copy(totalBlockedCount = it.totalBlockedCount + count) }
    }

    fun syncInstalledState(onComplete: ((Boolean) -> Unit)? = null) {
        val engine = engineRef ?: return
        mainHandler.post {
            engine.listInstalledWebExtensions(
                onSuccess = { list ->
                    val found = list.firstOrNull { it.id == UBLOCK_ID }
                    installedExt = found
                    if (found != null) {
                        val meta = found.getMetadata()
                        val ver = meta?.version.orEmpty()
                        val enabled = found.isEnabled()
                        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            ?.edit()?.putString(KEY_INSTALLED_VER, ver)?.apply()
                        if (!found.isAllowedInPrivateBrowsing()) {
                            engine.setAllowedInPrivateBrowsing(found, true)
                        }
                        _state.update {
                            it.copy(
                                isInstalled = true,
                                isEnabled = enabled,
                                version = ver.ifBlank { it.version },
                                optionsPageUrl = meta?.optionsPageUrl,
                                lastError = null,
                            )
                        }
                        onComplete?.invoke(true)
                    } else {
                        _state.update {
                            it.copy(
                                isInstalled = false,
                                optionsPageUrl = null,
                            )
                        }
                        onComplete?.invoke(false)
                    }
                },
                onError = {
                    onComplete?.invoke(false)
                }
            )
        }
    }

    fun cancelUpdate() {
        activeUpdateJob?.cancel()
        activeUpdateJob = null
        UhExtensionManager.cancelActiveOperation(UBLOCK_ID)
        _state.update { it.copy(isUpdating = false, lastError = null) }
    }

    fun checkAndInstallOrUpdate(force: Boolean = false) {
        val ctx = appContext ?: return
        val engine = engineRef ?: return
        if (_state.value.isUpdating) return

        _state.update { it.copy(isUpdating = true, lastError = null) }

        activeUpdateJob = scope.launch {
            try {
                withTimeout(UPDATE_TIMEOUT_MS) {
                    val addon = AmoApiClient.fetchAddon(UBLOCK_SLUG)
                    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

                    if (addon == null || addon.xpiUrl.isBlank()) {
                        throw IllegalStateException("Could not reach uBlock Origin package server")
                    }

                    val currentVer = _state.value.version
                    if (!force && _state.value.isInstalled && currentVer == addon.version) {
                        _state.update { it.copy(lastError = null) }
                        return@withTimeout
                    }

                    val installed = UhExtensionManager.installAutoApprovedAwait(
                        extensionId = UBLOCK_ID,
                        xpiUrl = addon.xpiUrl,
                        displayName = "uBlock Origin",
                    )

                    installedExt = installed
                    val desiredEnabled = SettingsPrefs.getBool(ctx, SettingsPrefs.K_UBLOCK_ENABLED, true)
                    mainHandler.post {
                        engine.setAllowedInPrivateBrowsing(installed, true)
                        if (!desiredEnabled) {
                            engine.disableWebExtension(installed, EnableSource.USER, onSuccess = {})
                        }
                    }

                    val meta = installed.getMetadata()
                    val ver = meta?.version?.ifBlank { addon.version } ?: addon.version
                    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(KEY_INSTALLED_VER, ver).apply()

                    _state.update {
                        it.copy(
                            isInstalled = true,
                            isEnabled = desiredEnabled,
                            version = ver,
                            optionsPageUrl = meta?.optionsPageUrl,
                            lastError = null,
                        )
                    }
                    UHLog.d("ublock", "uBlock Origin ready (v$ver)")
                }
            } catch (e: CancellationException) {
                UHLog.d("ublock", "uBlock Origin update cancelled")
                _state.update { it.copy(lastError = null) }
            } catch (e: Exception) {
                val msg = when {
                    e.message?.contains("timed out", ignoreCase = true) == true -> "Update timed out. Tap Retry."
                    e.message?.contains("package server", ignoreCase = true) == true -> "Offline or server unreachable"
                    else -> e.message?.take(64)?.ifBlank { "Update failed. Tap Retry." } ?: "Update failed. Tap Retry."
                }
                UHLog.w("ublock", "uBlock Origin install/update failed: ${e.message}")
                _state.update { it.copy(lastError = msg) }
            } finally {
                activeUpdateJob = null
                _state.update { it.copy(isUpdating = false) }
            }
        }
    }

    private fun readPausedHosts(ctx: Context): Set<String> {
        val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_PAUSED_HOSTS, emptySet())
        return raw?.toSet() ?: emptySet()
    }

    private fun savePausedHosts(ctx: Context, hosts: Set<String>) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_PAUSED_HOSTS, hosts).apply()
    }

    private fun extractHost(url: String): String? {
        return try {
            URI(url).host?.lowercase()?.removePrefix("www.")
        } catch (_: Exception) {
            null
        }
    }
}
