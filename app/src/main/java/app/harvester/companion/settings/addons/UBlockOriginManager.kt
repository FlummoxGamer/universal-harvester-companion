package app.harvester.companion.settings.addons

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.UHLog
import app.harvester.companion.core.network.UhHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File
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

    data class State(
        val isInstalled: Boolean = false,
        val isEnabled: Boolean = true,
        val isUpdating: Boolean = false,
        val version: String = "",
        val pausedHosts: Set<String> = emptySet(),
        val totalBlockedCount: Int = 0
    )

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var geckoRuntime: GeckoRuntime? = null
    @Volatile
    private var installedExt: WebExtension? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun init(ctx: Context, runtime: GeckoRuntime) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = ctx.applicationContext
        geckoRuntime = runtime

        val enabled = SettingsPrefs.getBool(ctx, SettingsPrefs.K_UBLOCK_ENABLED, true)
        val paused = readPausedHosts(ctx)
        val cachedVer = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_INSTALLED_VER, "") ?: ""
        _state.value = _state.value.copy(
            isEnabled = enabled,
            pausedHosts = paused,
            version = cachedVer
        )

        syncInstalledState { isPresent ->
            val autoUpdate = SettingsPrefs.getBool(ctx, SettingsPrefs.K_UBLOCK_AUTO_UPDATE, true)
            val lastCheck = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0L)
            val dueForUpdate = autoUpdate && (System.currentTimeMillis() - lastCheck > CHECK_INTERVAL_MS)
            if (enabled && (!isPresent || dueForUpdate)) {
                checkAndInstallOrUpdate(force = !isPresent)
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
        _state.value = _state.value.copy(pausedHosts = next)
        return nowPaused
    }

    fun setGloballyEnabled(enabled: Boolean) {
        val ctx = appContext ?: return
        val runtime = geckoRuntime ?: return
        SettingsPrefs.setBool(ctx, SettingsPrefs.K_UBLOCK_ENABLED, enabled)
        _state.value = _state.value.copy(isEnabled = enabled)

        mainHandler.post {
            val ext = installedExt
            if (ext != null) {
                if (enabled) {
                    runtime.webExtensionController.enable(ext, WebExtensionController.EnableSource.USER).accept({ updated ->
                        installedExt = updated
                        _state.value = _state.value.copy(isInstalled = true, isEnabled = true)
                    }, {})
                } else {
                    runtime.webExtensionController.disable(ext, WebExtensionController.EnableSource.USER).accept({ updated ->
                        installedExt = updated
                        _state.value = _state.value.copy(isEnabled = false)
                    }, {})
                }
            } else if (enabled) {
                checkAndInstallOrUpdate(force = true)
            }
        }
    }

    fun recordBlocked(count: Int = 1) {
        if (count <= 0) return
        _state.value = _state.value.copy(totalBlockedCount = _state.value.totalBlockedCount + count)
    }

    fun syncInstalledState(onComplete: ((Boolean) -> Unit)? = null) {
        val runtime = geckoRuntime ?: return
        mainHandler.post {
            runtime.webExtensionController.list().accept({ list ->
                val found = list?.firstOrNull { it.id == UBLOCK_ID }
                installedExt = found
                if (found != null) {
                    val ver = found.metaData.version
                    val enabled = found.metaData.enabled
                    appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        ?.edit()?.putString(KEY_INSTALLED_VER, ver)?.apply()
                    if (!found.metaData.allowedInPrivateBrowsing) {
                        mainHandler.post {
                            runtime.webExtensionController.setAllowedInPrivateBrowsing(found, true)
                        }
                    }
                    _state.value = _state.value.copy(
                        isInstalled = true,
                        isEnabled = enabled,
                        version = ver
                    )
                    onComplete?.invoke(true)
                } else {
                    _state.value = _state.value.copy(isInstalled = false)
                    onComplete?.invoke(false)
                }
            }, {
                onComplete?.invoke(false)
            })
        }
    }

    fun checkAndInstallOrUpdate(force: Boolean = false) {
        val ctx = appContext ?: return
        val runtime = geckoRuntime ?: return
        if (_state.value.isUpdating) return

        _state.value = _state.value.copy(isUpdating = true)
        scope.launch {
            try {
                val addon = AmoApiClient.fetchAddon(UBLOCK_SLUG)
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

                if (addon == null || addon.xpiUrl.isBlank()) {
                    UHLog.w("ublock", "Unable to query uBlock Origin release metadata from AMO.")
                    _state.value = _state.value.copy(isUpdating = false)
                    return@launch
                }

                val currentVer = _state.value.version
                if (!force && _state.value.isInstalled && currentVer == addon.version) {
                    _state.value = _state.value.copy(isUpdating = false)
                    return@launch
                }

                val extDir = File(ctx.filesDir, "extensions").apply { mkdirs() }
                val targetXpi = File(extDir, "ublock_origin.xpi")
                val tmpXpi = File(extDir, "ublock_origin.xpi.tmp")

                val req = Request.Builder()
                    .url(addon.xpiUrl)
                    .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                    .build()

                var downloaded = false
                UhHttpClient.client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful && resp.body != null) {
                        resp.body!!.byteStream().use { input ->
                            tmpXpi.outputStream().use { out -> input.copyTo(out) }
                        }
                        if (tmpXpi.length() > 10_000L) {
                            tmpXpi.renameTo(targetXpi)
                            downloaded = true
                        }
                    }
                }
                if (!downloaded) {
                    tmpXpi.delete()
                }

                val installSource = if (downloaded && targetXpi.isFile) {
                    Uri.fromFile(targetXpi).toString()
                } else {
                    addon.xpiUrl
                }

                mainHandler.post {
                    runtime.webExtensionController.install(installSource).accept({ ext ->
                        installedExt = ext
                        if (ext != null) {
                            mainHandler.post {
                                runtime.webExtensionController.setAllowedInPrivateBrowsing(ext, true)
                                if (!SettingsPrefs.getBool(ctx, SettingsPrefs.K_UBLOCK_ENABLED, true)) {
                                    runtime.webExtensionController.disable(ext, WebExtensionController.EnableSource.USER)
                                }
                            }
                            val ver = ext.metaData.version.ifBlank { addon.version }
                            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit().putString(KEY_INSTALLED_VER, ver).apply()
                            _state.value = _state.value.copy(
                                isInstalled = true,
                                isUpdating = false,
                                version = ver
                            )
                            UHLog.d("ublock", "uBlock Origin ready (v$ver)")
                        } else {
                            _state.value = _state.value.copy(isUpdating = false)
                        }
                    }, { err ->
                        UHLog.w("ublock", "Local file install fallback/error: ${err?.message}")
                        if (installSource != addon.xpiUrl) {
                            mainHandler.post {
                                runtime.webExtensionController.install(addon.xpiUrl).accept({ ext ->
                                    installedExt = ext
                                    if (ext != null) {
                                        mainHandler.post {
                                            runtime.webExtensionController.setAllowedInPrivateBrowsing(ext, true)
                                        }
                                    }
                                    _state.value = _state.value.copy(
                                        isInstalled = ext != null,
                                        isUpdating = false,
                                        version = ext?.metaData?.version ?: addon.version
                                    )
                                }, {
                                    _state.value = _state.value.copy(isUpdating = false)
                                })
                            }
                        } else {
                            _state.value = _state.value.copy(isUpdating = false)
                        }
                    })
                }
            } catch (e: Exception) {
                UHLog.w("ublock", "uBlock Origin auto-install failed: ${e.message}")
                _state.value = _state.value.copy(isUpdating = false)
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
