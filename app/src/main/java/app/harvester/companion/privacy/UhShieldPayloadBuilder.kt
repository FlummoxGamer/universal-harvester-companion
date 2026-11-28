package app.harvester.companion.privacy

import android.content.Context
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.ThemePrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds WebExtension runtime settings payloads, Binder-safe chunked rule streams,
 * per-host cosmetic selector lists, and GET_PAGE_PROTECT responses for UH Shield.
 */
internal object UhShieldPayloadBuilder {

    // Chunk sizes for Binder-safe Native Messaging transfers to uh_bridge
    private const val DOMAIN_CHUNK_SIZE = 8_000
    private const val STRUCTURED_RULE_CHUNK_SIZE = 1_200

    fun buildExtensionRuntimePayload(
        ctx: Context,
        state: UhShieldEngine.State,
        compiledSet: UhAdblockRuleCompiler.CompiledFilterSet,
        jsAllowOnceHosts: Set<String>,
        jsAllowAlwaysHosts: Set<String>,
        redirectAllowOnceHosts: Set<String> = emptySet(),
        fpSessionSalt: String = "",
    ): JSONObject {
        val pausedArr = JSONArray().apply { state.pausedHosts.forEach { put(it) } }
        val jsOnceArr = JSONArray().apply { jsAllowOnceHosts.forEach { put(it) } }
        val jsAlwaysArr = JSONArray().apply { jsAllowAlwaysHosts.forEach { put(it) } }
        val redirectOnceArr = JSONArray().apply { redirectAllowOnceHosts.forEach { put(it) } }
        val darkBypassArr = JSONArray().apply {
            SettingsPrefs.getStringSet(ctx, SettingsPrefs.K_DARK_BYPASS_HOSTS).forEach { put(it) }
        }
        val antiFingerprint = SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_FINGERPRINT, true)
        val clearUrls = SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_CLEAR_URLS, true)
        val shieldActive = state.level != ShieldLevel.OFF
        // Suppress legacy DNT signal automatically while Anti-Fingerprinting is active (avoids +2.02 bit leak)
        val effectiveDnt = !antiFingerprint && SettingsPrefs.getBool(ctx, SettingsPrefs.K_ADV_DNT, false)

        return JSONObject().apply {
            put("shieldLevel", state.level.code)
            put("totalRules", compiledSet.totalRules)
            put("pausedHosts", pausedArr)
            put("smartBlockJs", SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_SCRIPT, false))
            put("jsAllowOnceHosts", jsOnceArr)
            put("jsAllowAlwaysHosts", jsAlwaysArr)
            put("redirectAllowOnceHosts", redirectOnceArr)
            put("cookieBanners", SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_COOKIE_BANNERS, true))
            put("clearUrls", clearUrls)
            put("deAmp", SettingsPrefs.getBool(ctx, SettingsPrefs.K_ADV_DEAMP, true))
            put("blockPopups", SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_POPUP_BLOCK, true))
            put("antiFingerprint", antiFingerprint)
            put("strictFingerprint", false)
            put("fpSessionSalt", fpSessionSalt)
            put("refererTrim", antiFingerprint || clearUrls)
            put("lanGuard", shieldActive)
            put("annoyances", shieldActive)
            put("cnameUnmask", shieldActive)
            put("dnt", effectiveDnt)
            put("gpc", SettingsPrefs.getBool(ctx, SettingsPrefs.K_ADV_GPC, true))
            put("webrtcPolicy", SettingsPrefs.getInt(ctx, SettingsPrefs.K_ADV_WEBRTC_POLICY, 1))
            put("backgroundPlay", SettingsPrefs.getBool(ctx, SettingsPrefs.K_BACKGROUND_PLAY, true))
            val darkActive = UhPrivacyController.isDarkModeActive(ctx)
            put("forceDarkWeb", darkActive)
            put("enhanceDark", darkActive && ThemePrefs.isAmoled(ctx))
            put("darkBypassHosts", JSONArray())
        }
    }

    fun buildChunkedRuntimeMessages(
        ctx: Context,
        state: UhShieldEngine.State,
        compiledSet: UhAdblockRuleCompiler.CompiledFilterSet,
        jsAllowOnceHosts: Set<String>,
        jsAllowAlwaysHosts: Set<String>,
        redirectAllowOnceHosts: Set<String> = emptySet(),
        fpSessionSalt: String = "",
    ): List<JSONObject> {
        val syncId = System.nanoTime()
        val messages = ArrayList<JSONObject>(32)

        messages.add(
            JSONObject().apply {
                put("action", "SHIELD_CONFIG_BEGIN")
                put("syncId", syncId)
                put(
                    "settings",
                    buildExtensionRuntimePayload(
                        ctx = ctx,
                        state = state,
                        compiledSet = compiledSet,
                        jsAllowOnceHosts = jsAllowOnceHosts,
                        jsAllowAlwaysHosts = jsAllowAlwaysHosts,
                        redirectAllowOnceHosts = redirectAllowOnceHosts,
                        fpSessionSalt = fpSessionSalt,
                    )
                )
            }
        )

        fun appendDomainChunks(kind: String, domains: Set<String>) {
            if (domains.isEmpty()) return
            var arr = JSONArray()
            var count = 0
            for (d in domains) {
                arr.put(d)
                count++
                if (count >= DOMAIN_CHUNK_SIZE) {
                    messages.add(
                        JSONObject().apply {
                            put("action", "SHIELD_CONFIG_CHUNK")
                            put("syncId", syncId)
                            put("kind", kind)
                            put("items", arr)
                        }
                    )
                    arr = JSONArray()
                    count = 0
                }
            }
            if (count > 0) {
                messages.add(
                    JSONObject().apply {
                        put("action", "SHIELD_CONFIG_CHUNK")
                        put("syncId", syncId)
                        put("kind", kind)
                        put("items", arr)
                    }
                )
            }
        }

        appendDomainChunks("adDomains", compiledSet.adDomains)
        appendDomainChunks("trackerDomains", compiledSet.trackerDomains)
        appendDomainChunks("thirdPartyAdDomains", compiledSet.thirdPartyAdDomains)
        appendDomainChunks("thirdPartyTrackerDomains", compiledSet.thirdPartyTrackerDomains)
        val mergedPopups = HashSet<String>(compiledSet.popupDomains.size + UhShieldBootstrapRules.ALWAYS_POPUP_DOMAINS.size).apply {
            addAll(compiledSet.popupDomains)
            if (state.level != ShieldLevel.OFF) {
                addAll(UhShieldBootstrapRules.ALWAYS_POPUP_DOMAINS)
            }
        }
        appendDomainChunks("popupDomains", mergedPopups)
        appendDomainChunks("exceptionDomains", compiledSet.exceptionDomains)
        appendDomainChunks("thirdPartyExceptionDomains", compiledSet.thirdPartyExceptionDomains)

        if (compiledSet.networkRules.isNotEmpty()) {
            var arr = JSONArray()
            var count = 0
            for (rule in compiledSet.networkRules) {
                arr.put(rule.toWireJson())
                count++
                if (count >= STRUCTURED_RULE_CHUNK_SIZE) {
                    messages.add(
                        JSONObject().apply {
                            put("action", "SHIELD_CONFIG_CHUNK")
                            put("syncId", syncId)
                            put("kind", "networkRules")
                            put("items", arr)
                        }
                    )
                    arr = JSONArray()
                    count = 0
                }
            }
            if (count > 0) {
                messages.add(
                    JSONObject().apply {
                        put("action", "SHIELD_CONFIG_CHUNK")
                        put("syncId", syncId)
                        put("kind", "networkRules")
                        put("items", arr)
                    }
                )
            }
        }

        if (compiledSet.removeParamRules.isNotEmpty()) {
            val rpArr = JSONArray()
            for (rp in compiledSet.removeParamRules) {
                rpArr.put(rp.toWireJson())
            }
            messages.add(
                JSONObject().apply {
                    put("action", "SHIELD_CONFIG_CHUNK")
                    put("syncId", syncId)
                    put("kind", "removeParamRules")
                    put("items", rpArr)
                }
            )
        }

        messages.add(
            JSONObject().apply {
                put("action", "SHIELD_CONFIG_COMMIT")
                put("syncId", syncId)
                put("totalRules", compiledSet.totalRules)
            }
        )

        return messages
    }

    fun getCosmeticSelectorsForHost(
        pageUrl: String,
        state: UhShieldEngine.State,
        compiledSet: UhAdblockRuleCompiler.CompiledFilterSet,
    ): List<String> {
        if (state.level == ShieldLevel.OFF) return emptyList()
        val host = UhShieldUrlCleaner.normalizeHost(pageUrl)
        if (host.isNotBlank() && host in state.pausedHosts) return emptyList()

        if (compiledSet.totalRules == 0) return emptyList()

        val hostExceptions = HashSet<String>()
        val hostExcludedGlobals = HashSet<String>()
        val domainMatches = ArrayList<String>(24)

        if (host.isNotBlank()) {
            var candidate = host
            while (true) {
                compiledSet.domainCosmeticExceptions[candidate]?.let { hostExceptions.addAll(it) }
                compiledSet.excludedDomainCosmetics[candidate]?.let { hostExcludedGlobals.addAll(it) }
                compiledSet.domainCosmetics[candidate]?.let { domainMatches.addAll(it) }
                val dot = candidate.indexOf('.')
                if (dot < 0 || dot >= candidate.length - 1) break
                candidate = candidate.substring(dot + 1)
            }
        }

        val result = ArrayList<String>(compiledSet.globalCosmetics.size + domainMatches.size)
        for (sel in compiledSet.globalCosmetics) {
            if (sel !in hostExceptions && sel !in hostExcludedGlobals) {
                result.add(sel)
            }
        }
        for (sel in domainMatches) {
            if (sel !in hostExceptions) {
                result.add(sel)
            }
        }
        return result
    }

    fun buildPageProtectPayload(
        ctx: Context,
        pageUrl: String,
        state: UhShieldEngine.State,
        compiledSet: UhAdblockRuleCompiler.CompiledFilterSet,
        fpSessionSalt: String = "",
    ): JSONObject {
        val host = UhShieldUrlCleaner.normalizeHost(pageUrl)
        val paused = host.isNotBlank() && host in state.pausedHosts
        val shieldActive = state.level != ShieldLevel.OFF && !paused

        val selArr = JSONArray()
        val procArr = JSONArray()
        val scriptletsArr = JSONArray()
        val styleRulesArr = JSONArray()
        val removeSelArr = JSONArray()

        if (shieldActive && compiledSet.totalRules > 0) {
            getCosmeticSelectorsForHost(pageUrl, state, compiledSet).forEach { selArr.put(it) }

            compiledSet.globalProcedural.forEach { procArr.put(it.toWireJson()) }
            if (host.isNotBlank()) {
                var candidate = host
                while (true) {
                    compiledSet.domainProcedural[candidate]?.forEach { procArr.put(it.toWireJson()) }
                    compiledSet.domainScriptlets[candidate]?.forEach { scriptletsArr.put(it.toWireJson()) }
                    compiledSet.domainStyleRules[candidate]?.forEach { styleRulesArr.put(it.toWireJson()) }
                    compiledSet.domainRemoveRules[candidate]?.forEach { removeSelArr.put(it) }
                    val dot = candidate.indexOf('.')
                    if (dot < 0 || dot >= candidate.length - 1) break
                    candidate = candidate.substring(dot + 1)
                }
            }
        }

        val antiFingerprintPref = SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_FINGERPRINT, true)
        val antiFpActive = antiFingerprintPref && !paused
        val effectiveDnt = !antiFpActive && SettingsPrefs.getBool(ctx, SettingsPrefs.K_ADV_DNT, false)

        return JSONObject().apply {
            put("shieldLevel", state.level.code)
            put("paused", paused)
            put("cosmetics", selArr)
            put("procedural", procArr)
            put("scriptlets", scriptletsArr)
            put("styleRules", styleRulesArr)
            put("removeSelectors", removeSelArr)
            put("annoyances", shieldActive)
            put("cookieBanners", SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_COOKIE_BANNERS, true) && !paused)
            put("blockPopups", SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_POPUP_BLOCK, true) && !paused)
            put("antiFingerprint", antiFpActive)
            put("strictFingerprint", false)
            put("dnt", effectiveDnt)
            put("fpSessionSalt", fpSessionSalt)
            put("backgroundPlay", SettingsPrefs.getBool(ctx, SettingsPrefs.K_BACKGROUND_PLAY, true))
            put("webrtcPolicy", SettingsPrefs.getInt(ctx, SettingsPrefs.K_ADV_WEBRTC_POLICY, 1))
            val darkActive = UhPrivacyController.isDarkModeActive(ctx)
            put("forceDarkWeb", darkActive)
            put("enhanceDark", darkActive && ThemePrefs.isAmoled(ctx))
            put("darkBypassed", false)
        }
    }
}
