package app.harvester.companion.privacy

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Full-featured standard filter-list rule compiler.
 *
 * Compiles filter lists without artificial domain caps into:
 * - O(1) domain hash sets (all-party, third-party-only, popup-only, exceptions)
 * - Structured network rules (path/wildcard/regex, resource types, $domain, @@ exceptions, $important, $redirect)
 * - Global & domain-scoped CSS cosmetic rules + #@# cosmetic exceptions + procedural :has-text() rules
 * - Domain-scoped anti-adblock scriptlets (##+js(...))
 */
object UhAdblockRuleCompiler {

    const val TYPE_SCRIPT = 1 shl 0
    const val TYPE_IMAGE = 1 shl 1
    const val TYPE_STYLESHEET = 1 shl 2
    const val TYPE_SUBDOCUMENT = 1 shl 3
    const val TYPE_XHR = 1 shl 4
    const val TYPE_MEDIA = 1 shl 5
    const val TYPE_FONT = 1 shl 6
    const val TYPE_PING = 1 shl 7
    const val TYPE_OBJECT = 1 shl 8
    const val TYPE_OTHER = 1 shl 9
    const val TYPE_DOCUMENT = 1 shl 10
    const val TYPE_POPUP = 1 shl 11

    data class CompiledNetworkRule(
        val pattern: String,
        val anchorDomain: String? = null,
        val isRegex: Boolean = false,
        val isException: Boolean = false,
        val isTracker: Boolean = false,
        val thirdPartyOnly: Boolean = false,
        val firstPartyOnly: Boolean = false,
        val includeTypes: Int = 0,
        val excludeTypes: Int = 0,
        val includeDomains: List<String> = emptyList(),
        val excludeDomains: List<String> = emptyList(),
        val isPopup: Boolean = false,
        val important: Boolean = false,
        val redirect: String? = null,
    ) {
        /**
         * Compact wire format sent to uh_bridge WebExtension:
         * [pattern, flags, anchorDomain, includeTypes, excludeTypes, includeDomains, excludeDomains, redirect]
         * flags bitmask:
         *  1 = isRegex, 2 = isException, 4 = isTracker, 8 = thirdPartyOnly,
         *  16 = firstPartyOnly, 32 = isPopup, 64 = important
         */
        fun toWireJson(): JSONArray {
            var flags = 0
            if (isRegex) flags = flags or 1
            if (isException) flags = flags or 2
            if (isTracker) flags = flags or 4
            if (thirdPartyOnly) flags = flags or 8
            if (firstPartyOnly) flags = flags or 16
            if (isPopup) flags = flags or 32
            if (important) flags = flags or 64

            return JSONArray().apply {
                put(pattern)
                put(flags)
                put(anchorDomain ?: "")
                put(includeTypes)
                put(excludeTypes)
                if (includeDomains.isNotEmpty()) {
                    put(JSONArray().apply { includeDomains.forEach { put(it) } })
                } else {
                    put(JSONObject.NULL)
                }
                if (excludeDomains.isNotEmpty()) {
                    put(JSONArray().apply { excludeDomains.forEach { put(it) } })
                } else {
                    put(JSONObject.NULL)
                }
                put(redirect ?: "")
            }
        }
    }

    data class ProceduralCosmeticRule(
        val baseSelector: String,
        val textMatch: String,
        val isRegex: Boolean = false,
        val upwardLevel: Int = 0,
    ) {
        fun toWireJson(): JSONObject = JSONObject().apply {
            put("sel", baseSelector)
            put("text", textMatch)
            if (isRegex) put("re", true)
            if (upwardLevel > 0) put("up", upwardLevel)
        }
    }

    data class ScriptletRule(
        val name: String,
        val args: List<String>,
    ) {
        fun toWireJson(): JSONObject = JSONObject().apply {
            put("name", name)
            put("args", JSONArray().apply { args.forEach { put(it) } })
        }
    }

    data class StyleCosmeticRule(
        val selector: String,
        val cssDecl: String,
    ) {
        fun toWireJson(): JSONObject = JSONObject().apply {
            put("sel", selector)
            put("css", cssDecl)
        }
    }

    data class RemoveParamRule(
        val pattern: String,
        val param: String,
        val isRegexParam: Boolean = false,
        val includeDomains: List<String> = emptyList(),
        val excludeDomains: List<String> = emptyList(),
    ) {
        fun toWireJson(): JSONArray = JSONArray().apply {
            put(pattern)
            put(param)
            put(if (isRegexParam) 1 else 0)
            if (includeDomains.isNotEmpty()) {
                put(JSONArray().apply { includeDomains.forEach { put(it) } })
            } else {
                put(JSONObject.NULL)
            }
            if (excludeDomains.isNotEmpty()) {
                put(JSONArray().apply { excludeDomains.forEach { put(it) } })
            } else {
                put(JSONObject.NULL)
            }
        }
    }

    class Builder {
        val adDomains = HashSet<String>(16384)
        val trackerDomains = HashSet<String>(16384)
        val thirdPartyAdDomains = HashSet<String>(8192)
        val thirdPartyTrackerDomains = HashSet<String>(8192)
        val popupDomains = HashSet<String>(2048)
        val exceptionDomains = HashSet<String>(2048)
        val thirdPartyExceptionDomains = HashSet<String>(1024)

        val networkRules = ArrayList<CompiledNetworkRule>(4096)
        private val networkRuleDedup = HashSet<String>(4096)

        val globalCosmetics = LinkedHashSet<String>(512)
        val globalCosmeticExceptions = HashSet<String>(128)
        val domainCosmetics = HashMap<String, MutableList<String>>(2048)
        val domainCosmeticExceptions = HashMap<String, MutableSet<String>>(512)
        val excludedDomainCosmetics = HashMap<String, MutableSet<String>>(256)

        val globalProcedural = ArrayList<ProceduralCosmeticRule>(64)
        val domainProcedural = HashMap<String, MutableList<ProceduralCosmeticRule>>(256)

        val domainScriptlets = HashMap<String, MutableList<ScriptletRule>>(512)
        val domainStyleRules = HashMap<String, MutableList<StyleCosmeticRule>>(256)
        val domainRemoveRules = HashMap<String, MutableList<String>>(256)
        val removeParamRules = ArrayList<RemoveParamRule>(256)
        private val removeParamDedup = HashSet<String>(256)

        fun addRemoveParamRule(rule: RemoveParamRule) {
            if (removeParamRules.size >= MAX_REMOVE_PARAM_RULES) return
            val key = "${rule.pattern}|${rule.param}|${rule.isRegexParam}|${rule.includeDomains}"
            if (removeParamDedup.add(key)) {
                removeParamRules.add(rule)
            }
        }

        fun addNetworkRule(rule: CompiledNetworkRule) {
            // Keep structured rules well-bounded in memory while accommodating full multi-list sets
            if (networkRules.size >= MAX_STRUCTURED_RULES && !rule.isException && !rule.important) return
            val key = "${rule.isException}|${rule.pattern}|${rule.anchorDomain}|${rule.includeTypes}|${rule.excludeTypes}|${rule.thirdPartyOnly}|${rule.firstPartyOnly}|${rule.includeDomains}|${rule.redirect}"
            if (networkRuleDedup.add(key)) {
                networkRules.add(rule)
            }
        }

        fun build(): CompiledFilterSet {
            // Remove globally exempted pure domains from unconditional domain sets
            adDomains.removeAll(exceptionDomains)
            trackerDomains.removeAll(exceptionDomains)
            thirdPartyAdDomains.removeAll(exceptionDomains)
            thirdPartyTrackerDomains.removeAll(exceptionDomains)
            thirdPartyAdDomains.removeAll(thirdPartyExceptionDomains)
            thirdPartyTrackerDomains.removeAll(thirdPartyExceptionDomains)
            popupDomains.removeAll(exceptionDomains)

            // Deduplicate domain overlaps: all-party supersedes third-party, and Ads take priority over Trackers
            thirdPartyAdDomains.removeAll(adDomains)
            trackerDomains.removeAll(adDomains)
            thirdPartyTrackerDomains.removeAll(adDomains)
            thirdPartyTrackerDomains.removeAll(thirdPartyAdDomains)
            thirdPartyTrackerDomains.removeAll(trackerDomains)

            // Remove globally exempted cosmetic selectors
            globalCosmetics.removeAll(globalCosmeticExceptions)

            val total = adDomains.size +
                trackerDomains.size +
                thirdPartyAdDomains.size +
                thirdPartyTrackerDomains.size +
                popupDomains.size +
                exceptionDomains.size +
                thirdPartyExceptionDomains.size +
                networkRules.size +
                globalCosmetics.size +
                domainCosmetics.values.sumOf { it.size } +
                globalProcedural.size +
                domainProcedural.values.sumOf { it.size } +
                domainScriptlets.values.sumOf { it.size } +
                domainStyleRules.values.sumOf { it.size } +
                domainRemoveRules.values.sumOf { it.size } +
                removeParamRules.size

            return CompiledFilterSet(
                adDomains = adDomains,
                trackerDomains = trackerDomains,
                thirdPartyAdDomains = thirdPartyAdDomains,
                thirdPartyTrackerDomains = thirdPartyTrackerDomains,
                popupDomains = popupDomains,
                exceptionDomains = exceptionDomains,
                thirdPartyExceptionDomains = thirdPartyExceptionDomains,
                networkRules = networkRules,
                globalCosmetics = globalCosmetics.toList(),
                globalCosmeticExceptions = globalCosmeticExceptions,
                domainCosmetics = domainCosmetics,
                domainCosmeticExceptions = domainCosmeticExceptions,
                excludedDomainCosmetics = excludedDomainCosmetics,
                globalProcedural = globalProcedural,
                domainProcedural = domainProcedural,
                domainScriptlets = domainScriptlets,
                domainStyleRules = domainStyleRules,
                domainRemoveRules = domainRemoveRules,
                removeParamRules = removeParamRules,
                totalRules = total,
            )
        }
    }

    data class CompiledFilterSet(
        val adDomains: Set<String> = emptySet(),
        val trackerDomains: Set<String> = emptySet(),
        val thirdPartyAdDomains: Set<String> = emptySet(),
        val thirdPartyTrackerDomains: Set<String> = emptySet(),
        val popupDomains: Set<String> = emptySet(),
        val exceptionDomains: Set<String> = emptySet(),
        val thirdPartyExceptionDomains: Set<String> = emptySet(),
        val networkRules: List<CompiledNetworkRule> = emptyList(),
        val globalCosmetics: List<String> = emptyList(),
        val globalCosmeticExceptions: Set<String> = emptySet(),
        val domainCosmetics: Map<String, List<String>> = emptyMap(),
        val domainCosmeticExceptions: Map<String, Set<String>> = emptyMap(),
        val excludedDomainCosmetics: Map<String, Set<String>> = emptyMap(),
        val globalProcedural: List<ProceduralCosmeticRule> = emptyList(),
        val domainProcedural: Map<String, List<ProceduralCosmeticRule>> = emptyMap(),
        val domainScriptlets: Map<String, List<ScriptletRule>> = emptyMap(),
        val domainStyleRules: Map<String, List<StyleCosmeticRule>> = emptyMap(),
        val domainRemoveRules: Map<String, List<String>> = emptyMap(),
        val removeParamRules: List<RemoveParamRule> = emptyList(),
        val totalRules: Int = 0,
    ) {
        companion object {
            val EMPTY = CompiledFilterSet()
        }
    }

    /**
     * Merges multiple pre-compiled per-list [CompiledFilterSet]s in memory with zero disk I/O
     * or filter string reparsing, keeping 400k-500k rule toggles instantaneous and lag-free.
     */
    fun mergeSets(sets: List<CompiledFilterSet>): CompiledFilterSet {
        if (sets.isEmpty()) return CompiledFilterSet.EMPTY
        if (sets.size == 1) return sets[0]

        val adDomains = HashSet<String>(sets.sumOf { it.adDomains.size }.coerceAtLeast(16))
        val trackerDomains = HashSet<String>(sets.sumOf { it.trackerDomains.size }.coerceAtLeast(16))
        val thirdPartyAdDomains = HashSet<String>(sets.sumOf { it.thirdPartyAdDomains.size }.coerceAtLeast(16))
        val thirdPartyTrackerDomains = HashSet<String>(sets.sumOf { it.thirdPartyTrackerDomains.size }.coerceAtLeast(16))
        val popupDomains = HashSet<String>(sets.sumOf { it.popupDomains.size }.coerceAtLeast(16))
        val exceptionDomains = HashSet<String>(sets.sumOf { it.exceptionDomains.size }.coerceAtLeast(16))
        val thirdPartyExceptionDomains = HashSet<String>(sets.sumOf { it.thirdPartyExceptionDomains.size }.coerceAtLeast(16))

        val networkRules = ArrayList<CompiledNetworkRule>(
            sets.sumOf { it.networkRules.size }.coerceAtMost(MAX_STRUCTURED_RULES)
        )
        val networkDedup = HashSet<String>(4096)

        val globalCosmetics = LinkedHashSet<String>(MAX_GLOBAL_COSMETICS)
        val globalCosmeticExceptions = HashSet<String>(128)
        val domainCosmetics = HashMap<String, MutableList<String>>(2048)
        val domainCosmeticExceptions = HashMap<String, MutableSet<String>>(512)
        val excludedDomainCosmetics = HashMap<String, MutableSet<String>>(256)

        val globalProcedural = ArrayList<ProceduralCosmeticRule>(MAX_GLOBAL_PROCEDURAL)
        val domainProcedural = HashMap<String, MutableList<ProceduralCosmeticRule>>(256)
        val domainScriptlets = HashMap<String, MutableList<ScriptletRule>>(512)
        val domainStyleRules = HashMap<String, MutableList<StyleCosmeticRule>>(256)
        val domainRemoveRules = HashMap<String, MutableList<String>>(256)
        val removeParamRules = ArrayList<RemoveParamRule>(256)
        val removeParamDedup = HashSet<String>(256)

        for (set in sets) {
            adDomains.addAll(set.adDomains)
            trackerDomains.addAll(set.trackerDomains)
            thirdPartyAdDomains.addAll(set.thirdPartyAdDomains)
            thirdPartyTrackerDomains.addAll(set.thirdPartyTrackerDomains)
            popupDomains.addAll(set.popupDomains)
            exceptionDomains.addAll(set.exceptionDomains)
            thirdPartyExceptionDomains.addAll(set.thirdPartyExceptionDomains)

            for (rule in set.networkRules) {
                if (networkRules.size >= MAX_STRUCTURED_RULES && !rule.isException && !rule.important) continue
                val key = "${rule.isException}|${rule.pattern}|${rule.anchorDomain}|${rule.includeTypes}|${rule.excludeTypes}|${rule.thirdPartyOnly}|${rule.firstPartyOnly}|${rule.includeDomains}|${rule.redirect}"
                if (networkDedup.add(key)) {
                    networkRules.add(rule)
                }
            }

            for (sel in set.globalCosmetics) {
                if (globalCosmetics.size < MAX_GLOBAL_COSMETICS) globalCosmetics.add(sel)
            }
            globalCosmeticExceptions.addAll(set.globalCosmeticExceptions)

            for ((dom, list) in set.domainCosmetics) {
                val target = domainCosmetics.getOrPut(dom) { ArrayList(list.size) }
                for (sel in list) {
                    if (target.size < MAX_DOMAIN_COSMETICS_PER_HOST && sel !in target) target.add(sel)
                }
            }
            for ((dom, exSet) in set.domainCosmeticExceptions) {
                domainCosmeticExceptions.getOrPut(dom) { HashSet() }.addAll(exSet)
            }
            for ((dom, exSet) in set.excludedDomainCosmetics) {
                excludedDomainCosmetics.getOrPut(dom) { HashSet() }.addAll(exSet)
            }

            for (proc in set.globalProcedural) {
                if (globalProcedural.size < MAX_GLOBAL_PROCEDURAL) globalProcedural.add(proc)
            }
            for ((dom, list) in set.domainProcedural) {
                val target = domainProcedural.getOrPut(dom) { ArrayList(list.size) }
                for (p in list) {
                    if (target.size < MAX_DOMAIN_PROCEDURAL_PER_HOST) target.add(p)
                }
            }
            for ((dom, list) in set.domainScriptlets) {
                val target = domainScriptlets.getOrPut(dom) { ArrayList(list.size) }
                for (sc in list) {
                    if (target.size < MAX_SCRIPTLETS_PER_HOST && sc !in target) target.add(sc)
                }
            }
            for ((dom, list) in set.domainStyleRules) {
                val target = domainStyleRules.getOrPut(dom) { ArrayList(list.size) }
                for (st in list) {
                    if (target.size < MAX_STYLE_RULES_PER_HOST && st !in target) target.add(st)
                }
            }
            for ((dom, list) in set.domainRemoveRules) {
                val target = domainRemoveRules.getOrPut(dom) { ArrayList(list.size) }
                for (sel in list) {
                    if (target.size < MAX_REMOVE_RULES_PER_HOST && sel !in target) target.add(sel)
                }
            }
            for (rp in set.removeParamRules) {
                if (removeParamRules.size >= MAX_REMOVE_PARAM_RULES) break
                val key = "${rp.pattern}|${rp.param}|${rp.isRegexParam}|${rp.includeDomains}"
                if (removeParamDedup.add(key)) removeParamRules.add(rp)
            }
        }

        // Apply global exceptions & deduplicate overlaps (Ads supersede Trackers for accurate classification)
        adDomains.removeAll(exceptionDomains)
        trackerDomains.removeAll(exceptionDomains)
        thirdPartyAdDomains.removeAll(exceptionDomains)
        thirdPartyTrackerDomains.removeAll(exceptionDomains)
        thirdPartyAdDomains.removeAll(thirdPartyExceptionDomains)
        thirdPartyTrackerDomains.removeAll(thirdPartyExceptionDomains)
        popupDomains.removeAll(exceptionDomains)
        thirdPartyAdDomains.removeAll(adDomains)
        trackerDomains.removeAll(adDomains)
        thirdPartyTrackerDomains.removeAll(adDomains)
        thirdPartyTrackerDomains.removeAll(thirdPartyAdDomains)
        thirdPartyTrackerDomains.removeAll(trackerDomains)
        globalCosmetics.removeAll(globalCosmeticExceptions)

        val total = adDomains.size +
            trackerDomains.size +
            thirdPartyAdDomains.size +
            thirdPartyTrackerDomains.size +
            popupDomains.size +
            exceptionDomains.size +
            thirdPartyExceptionDomains.size +
            networkRules.size +
            globalCosmetics.size +
            domainCosmetics.values.sumOf { it.size } +
            globalProcedural.size +
            domainProcedural.values.sumOf { it.size } +
            domainScriptlets.values.sumOf { it.size } +
            domainStyleRules.values.sumOf { it.size } +
            domainRemoveRules.values.sumOf { it.size } +
            removeParamRules.size

        return CompiledFilterSet(
            adDomains = adDomains,
            trackerDomains = trackerDomains,
            thirdPartyAdDomains = thirdPartyAdDomains,
            thirdPartyTrackerDomains = thirdPartyTrackerDomains,
            popupDomains = popupDomains,
            exceptionDomains = exceptionDomains,
            thirdPartyExceptionDomains = thirdPartyExceptionDomains,
            networkRules = networkRules,
            globalCosmetics = globalCosmetics.toList(),
            globalCosmeticExceptions = globalCosmeticExceptions,
            domainCosmetics = domainCosmetics,
            domainCosmeticExceptions = domainCosmeticExceptions,
            excludedDomainCosmetics = excludedDomainCosmetics,
            globalProcedural = globalProcedural,
            domainProcedural = domainProcedural,
            domainScriptlets = domainScriptlets,
            domainStyleRules = domainStyleRules,
            domainRemoveRules = domainRemoveRules,
            removeParamRules = removeParamRules,
            totalRules = total,
        )
    }

    private const val MAX_STRUCTURED_RULES = 120_000
    private const val MAX_GLOBAL_COSMETICS = 4_000
    private const val MAX_DOMAIN_COSMETICS_PER_HOST = 180
    private const val MAX_GLOBAL_PROCEDURAL = 250
    private const val MAX_DOMAIN_PROCEDURAL_PER_HOST = 40
    private const val MAX_SCRIPTLETS_PER_HOST = 24
    private const val MAX_STYLE_RULES_PER_HOST = 40
    private const val MAX_REMOVE_RULES_PER_HOST = 40
    private const val MAX_REMOVE_PARAM_RULES = 3_000

    private val SUPPORTED_SCRIPTLET_ALIASES: Map<String, String> = mapOf(
        "abort-on-property-read" to "abort-on-property-read",
        "aopr" to "abort-on-property-read",
        "abort-on-property-write" to "abort-on-property-write",
        "aopw" to "abort-on-property-write",
        "abort-current-inline-script" to "abort-current-inline-script",
        "abort-current-script" to "abort-current-inline-script",
        "acis" to "abort-current-inline-script",
        "set-constant" to "set-constant",
        "set" to "set-constant",
        "no-settimeout-if" to "no-setTimeout-if",
        "nostif" to "no-setTimeout-if",
        "settimeout-defuser" to "no-setTimeout-if",
        "no-setinterval-if" to "no-setInterval-if",
        "nosiif" to "no-setInterval-if",
        "setinterval-defuser" to "no-setInterval-if",
        "no-window-open-if" to "window.open-defuser",
        "nowoif" to "window.open-defuser",
        "window.open-defuser" to "window.open-defuser",
        "json-prune" to "json-prune",
        "no-fetch-if" to "noop-fetch",
        "noop-fetch" to "noop-fetch",
        "bab-defuser" to "bab-defuser",
        "nofab" to "bab-defuser",
        "fuckadblock.js-3.2.0" to "bab-defuser",
        "addEventListener-defuser" to "addEventListener-defuser",
        "addeventlistener-defuser" to "addEventListener-defuser",
        "aeld" to "addEventListener-defuser",
        "remove-attr" to "remove-attr",
        "ra" to "remove-attr",
        "set-attr" to "set-attr",
        "overlay-buster" to "overlay-buster",
    )

    fun parseLines(
        lines: Sequence<String>,
        isTrackerList: Boolean,
        builder: Builder,
    ) {
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.length < 3) continue
            val first = line[0]
            if (first == '!' || first == '[') continue
            if (first == '#' && !line.startsWith("##") && !line.startsWith("#@#") && !line.startsWith("#?#")) continue

            // 1) Hosts-file syntax: 0.0.0.0 domain.com or 127.0.0.1 domain.com
            if (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) {
                val dom = line.substringAfter(' ').trim()
                    .substringBefore(' ')
                    .substringBefore('\t')
                    .substringBefore('#')
                    .lowercase(Locale.ROOT)
                if (isValidDomain(dom)) {
                    if (isTrackerList) builder.trackerDomains.add(dom) else builder.adDomains.add(dom)
                }
                continue
            }

            // 2) Cosmetic exception rule: domain#@#selector or #@#selector
            val excCosIdx = line.indexOf("#@#")
            if (excCosIdx >= 0) {
                parseCosmeticException(line, excCosIdx, builder)
                continue
            }

            // 3) Procedural extended cosmetic: domain#?#selector
            val extCosIdx = line.indexOf("#?#")
            if (extCosIdx >= 0) {
                parseProceduralCosmetic(line, extCosIdx, 3, builder)
                continue
            }

            // 4) Standard cosmetic or scriptlet rule: domain##selector or domain##+js(...)
            val cosIdx = line.indexOf("##")
            if (cosIdx >= 0) {
                val domainsPart = line.substring(0, cosIdx)
                val body = line.substring(cosIdx + 2).trim()
                if (body.startsWith("+js(") && body.endsWith(")")) {
                    parseScriptletRule(domainsPart, body, builder)
                } else if (body.contains(":style(") || body.endsWith(":remove()")) {
                    parseStyleOrRemoveCosmetic(domainsPart, body, builder)
                } else if (body.contains(":has-text(") || body.contains(":-abp-contains(")) {
                    parseProceduralCosmetic(line, cosIdx, 2, builder)
                } else {
                    parseStandardCosmetic(domainsPart, body, builder)
                }
                continue
            }

            // Skip non-standard HTML/CSS injection markers that aren't standard CSS/network rules
            if (line.contains("#$#") || line.contains("#%#") || line.contains("$$") || line.contains("$@#")) {
                continue
            }

            // 5) Network rule (including @@ exception rules, $options, wildcards, paths, regex)
            parseNetworkRule(line, isTrackerList, builder)
        }
    }

    private fun parseStandardCosmetic(domainsPart: String, selector: String, builder: Builder) {
        if (selector.length !in 2..200) return
        // Reject non-standard extended pseudo-selectors Gecko CSS doesn't support natively
        // Note: Standard :has(...) IS supported natively in Gecko 121+
        if (selector.contains("{") || selector.contains("}") ||
            selector.contains(":style(") || selector.contains(":remove(") ||
            selector.contains(":upward(") || selector.contains(":xpath(") ||
            selector.contains(":matches-css(") || selector.contains(":min-text-length(") ||
            selector.contains(":watch-attr(") || selector.contains(":others(") ||
            selector.contains(":-abp-")
        ) return

        if (domainsPart.isBlank()) {
            if (builder.globalCosmetics.size < MAX_GLOBAL_COSMETICS) {
                builder.globalCosmetics.add(selector)
            }
            return
        }

        val includes = mutableListOf<String>()
        val excludes = mutableListOf<String>()
        for (raw in domainsPart.split(',')) {
            val d = raw.trim().lowercase(Locale.ROOT)
            if (d.isBlank()) continue
            if (d.startsWith("~")) {
                val clean = d.substring(1)
                if (clean.contains('.')) excludes.add(clean)
            } else if (d.contains('.')) {
                includes.add(d)
            }
        }

        if (includes.isEmpty() && excludes.isNotEmpty()) {
            // Generic cosmetic with excluded domains (e.g., ~example.com##.ad-box)
            if (builder.globalCosmetics.size < MAX_GLOBAL_COSMETICS) {
                builder.globalCosmetics.add(selector)
                for (ex in excludes) {
                    builder.excludedDomainCosmetics.getOrPut(ex) { HashSet() }.add(selector)
                }
            }
        } else {
            for (inc in includes) {
                val list = builder.domainCosmetics.getOrPut(inc) { ArrayList() }
                if (list.size < MAX_DOMAIN_COSMETICS_PER_HOST && !list.contains(selector)) {
                    list.add(selector)
                }
            }
            for (ex in excludes) {
                builder.domainCosmeticExceptions.getOrPut(ex) { HashSet() }.add(selector)
            }
        }
    }

    private fun parseStyleOrRemoveCosmetic(domainsPart: String, body: String, builder: Builder) {
        if (domainsPart.isBlank()) return // Keep :style() and :remove() domain-scoped for safety
        val styleIdx = body.indexOf(":style(")
        if (styleIdx > 0 && body.endsWith(")")) {
            val sel = body.substring(0, styleIdx).trim()
            val cssDecl = body.substring(styleIdx + 7, body.length - 1).trim()
            if (sel.length !in 2..160 || cssDecl.length !in 3..240) return
            if (sel.contains("{") || sel.contains("}") || cssDecl.contains("{") || cssDecl.contains("}")) return
            val rule = StyleCosmeticRule(sel, cssDecl)
            for (raw in domainsPart.split(',')) {
                val d = raw.trim().lowercase(Locale.ROOT)
                if (d.contains('.') && !d.startsWith("~")) {
                    val list = builder.domainStyleRules.getOrPut(d) { ArrayList() }
                    if (list.size < MAX_STYLE_RULES_PER_HOST && rule !in list) list.add(rule)
                }
            }
            return
        }
        if (body.endsWith(":remove()")) {
            val sel = body.removeSuffix(":remove()").trim()
            if (sel.length !in 2..160 || sel.contains("{") || sel.contains("}") || sel.contains(":xpath(")) return
            for (raw in domainsPart.split(',')) {
                val d = raw.trim().lowercase(Locale.ROOT)
                if (d.contains('.') && !d.startsWith("~")) {
                    val list = builder.domainRemoveRules.getOrPut(d) { ArrayList() }
                    if (list.size < MAX_REMOVE_RULES_PER_HOST && sel !in list) list.add(sel)
                }
            }
        }
    }

    private fun parseCosmeticException(line: String, idx: Int, builder: Builder) {
        val selector = line.substring(idx + 3).trim()
        if (selector.length !in 2..200) return
        val domainsPart = line.substring(0, idx).trim()
        if (domainsPart.isBlank()) {
            builder.globalCosmeticExceptions.add(selector)
            return
        }
        for (raw in domainsPart.split(',')) {
            val d = raw.trim().lowercase(Locale.ROOT).removePrefix("~")
            if (d.contains('.')) {
                builder.domainCosmeticExceptions.getOrPut(d) { HashSet() }.add(selector)
            }
        }
    }

    private fun parseProceduralCosmetic(line: String, sepIdx: Int, sepLen: Int, builder: Builder) {
        val body = line.substring(sepIdx + sepLen).trim()
        val marker = when {
            body.contains(":has-text(") -> ":has-text("
            body.contains(":-abp-contains(") -> ":-abp-contains("
            else -> return
        }
        val mIdx = body.indexOf(marker)
        val baseSel = body.substring(0, mIdx).trim()
        if (baseSel.isBlank() || baseSel.length > 140 || baseSel.contains(":style(") || baseSel.contains(":xpath(")) return
        val afterMarker = body.substring(mIdx + marker.length)
        val closeParen = afterMarker.indexOf(')')
        if (closeParen <= 0) return
        val rawArg = afterMarker.substring(0, closeParen).trim()
        if (rawArg.isBlank() || rawArg.length > 100) return

        // Check optional :upward(N)
        var upward = 0
        val tail = afterMarker.substring(closeParen + 1).trim()
        if (tail.startsWith(":upward(") && tail.endsWith(")")) {
            upward = tail.substring(8, tail.length - 1).trim().toIntOrNull()?.coerceIn(1, 5) ?: 0
        } else if (tail.isNotEmpty()) {
            // Skip complex chained procedural expressions we don't emulate
            return
        }

        val isRegex = rawArg.length > 2 && rawArg.startsWith("/") && rawArg.endsWith("/")
        val cleanText = if (isRegex) rawArg.substring(1, rawArg.length - 1) else rawArg.trim('"', '\'')
        if (cleanText.isBlank()) return

        val rule = ProceduralCosmeticRule(
            baseSelector = baseSel,
            textMatch = cleanText,
            isRegex = isRegex,
            upwardLevel = upward,
        )

        val domainsPart = line.substring(0, sepIdx).trim()
        if (domainsPart.isBlank()) {
            if (builder.globalProcedural.size < MAX_GLOBAL_PROCEDURAL) {
                builder.globalProcedural.add(rule)
            }
        } else {
            for (raw in domainsPart.split(',')) {
                val d = raw.trim().lowercase(Locale.ROOT)
                if (d.contains('.') && !d.startsWith("~")) {
                    val list = builder.domainProcedural.getOrPut(d) { ArrayList() }
                    if (list.size < MAX_DOMAIN_PROCEDURAL_PER_HOST) {
                        list.add(rule)
                    }
                }
            }
        }
    }

    private fun parseScriptletRule(domainsPart: String, body: String, builder: Builder) {
        if (domainsPart.isBlank()) return // Never apply global untargeted scriptlets for stability
        val inner = body.substring(4, body.length - 1).trim()
        if (inner.isBlank()) return

        val parts = splitScriptletArgs(inner)
        if (parts.isEmpty()) return
        val rawName = parts[0].trim().lowercase(Locale.ROOT).removeSuffix(".js")
        val canonical = SUPPORTED_SCRIPTLET_ALIASES[rawName] ?: return
        val args = parts.drop(1).map { it.trim() }
        val scriptlet = ScriptletRule(canonical, args)

        for (raw in domainsPart.split(',')) {
            val d = raw.trim().lowercase(Locale.ROOT)
            if (d.contains('.') && !d.startsWith("~")) {
                val list = builder.domainScriptlets.getOrPut(d) { ArrayList() }
                if (list.size < MAX_SCRIPTLETS_PER_HOST && !list.contains(scriptlet)) {
                    list.add(scriptlet)
                }
            }
        }
    }

    private fun splitScriptletArgs(inner: String): List<String> {
        val out = ArrayList<String>(4)
        val cur = StringBuilder()
        var inSingle = false
        var inDouble = false
        var escaped = false
        for (ch in inner) {
            if (escaped) {
                cur.append(ch)
                escaped = false
                continue
            }
            when (ch) {
                '\\' -> escaped = true
                '\'' -> if (!inDouble) inSingle = !inSingle else cur.append(ch)
                '"' -> if (!inSingle) inDouble = !inDouble else cur.append(ch)
                ',' -> if (!inSingle && !inDouble) {
                    out.add(cur.toString().trim())
                    cur.setLength(0)
                } else {
                    cur.append(ch)
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString().trim())
        return out
    }

    private fun parseNetworkRule(line: String, isTrackerList: Boolean, builder: Builder) {
        val isException = line.startsWith("@@")
        val body = if (isException) line.substring(2) else line
        if (body.isBlank()) return

        // Split pattern and $options carefully (ignore $ inside /regex/)
        val isRegexBody = body.startsWith("/") && body.indexOf('/', 1) > 1
        var patternPart = body
        var optionsPart = ""

        if (isRegexBody) {
            val endSlash = body.lastIndexOf('/')
            val dollarAfter = body.indexOf('$', endSlash + 1)
            if (dollarAfter > 0) {
                patternPart = body.substring(0, dollarAfter)
                optionsPart = body.substring(dollarAfter + 1)
            }
        } else {
            val dollarIdx = body.lastIndexOf('$')
            if (dollarIdx > 0 && dollarIdx < body.length - 1) {
                // Verify text after $ looks like ABP options (not part of a URL query like $foo=bar with spaces/slashes)
                val candOpts = body.substring(dollarIdx + 1)
                if (!candOpts.contains('/') || candOpts.contains("domain=") || candOpts.contains("redirect")) {
                    patternPart = body.substring(0, dollarIdx)
                    optionsPart = candOpts
                }
            }
        }

        if (patternPart.isBlank()) return

        var thirdPartyOnly = false
        var firstPartyOnly = false
        var includeTypes = 0
        var excludeTypes = 0
        var isPopup = false
        var important = false
        var redirect: String? = null
        var removeParam: String? = null
        var hasUnsupportedBlockingModifier = false
        val includeDomains = ArrayList<String>(2)
        val excludeDomains = ArrayList<String>(2)

        if (optionsPart.isNotBlank()) {
            for (rawOpt in optionsPart.split(',')) {
                val opt = rawOpt.trim()
                if (opt.isEmpty()) continue
                val lower = opt.lowercase(Locale.ROOT)
                when {
                    lower == "third-party" || lower == "3p" || lower == "~first-party" || lower == "~1p" -> {
                        thirdPartyOnly = true
                    }
                    lower == "first-party" || lower == "1p" || lower == "~third-party" || lower == "~3p" -> {
                        firstPartyOnly = true
                    }
                    lower == "important" -> important = true
                    lower == "popup" -> {
                        isPopup = true
                        includeTypes = includeTypes or TYPE_POPUP
                    }
                    lower == "all" -> {
                        // Matches all types including popup
                        isPopup = true
                    }
                    lower.startsWith("removeparam=") -> {
                        val rawParam = opt.substringAfter('=').trim()
                        if (rawParam.isNotBlank() && !rawParam.startsWith("~")) {
                            removeParam = rawParam
                        } else {
                            hasUnsupportedBlockingModifier = true
                        }
                    }
                    lower == "badfilter" || lower == "csp" || lower.startsWith("csp=") ||
                        lower.startsWith("replace=") ||
                        lower == "elemhide" || lower == "generichide" || lower == "specifichide" ||
                        lower == "genericblock" || lower == "ehide" || lower == "ghide" || lower == "shide" -> {
                        // Modifier not handled as a simple request block; skip rule safely so we never over-block
                        hasUnsupportedBlockingModifier = true
                    }
                    lower.startsWith("redirect=") || lower.startsWith("redirect-rule=") -> {
                        val target = lower.substringAfter('=')
                        redirect = mapRedirectTarget(target)
                    }
                    lower.startsWith("domain=") || lower.startsWith("from=") -> {
                        val domList = lower.substringAfter('=')
                        for (rawDom in domList.split('|')) {
                            val d = rawDom.trim()
                            if (d.isBlank()) continue
                            if (d.startsWith("~")) {
                                val clean = d.substring(1)
                                if (clean.contains('.')) excludeDomains.add(clean)
                            } else if (d.contains('.')) {
                                includeDomains.add(d)
                            }
                        }
                    }
                    lower.startsWith("~") -> {
                        val bit = mapResourceType(lower.substring(1))
                        if (bit != 0) excludeTypes = excludeTypes or bit
                    }
                    else -> {
                        val bit = mapResourceType(lower)
                        if (bit != 0) includeTypes = includeTypes or bit
                    }
                }
            }
        }

        if (hasUnsupportedBlockingModifier) return

        // Handle $removeparam= rules directly without turning them into request cancellations
        if (removeParam != null) {
            if (isException) return
            val rpVal = removeParam!!
            val isRegexParam = rpVal.length > 2 && rpVal.startsWith("/") && rpVal.endsWith("/")
            val cleanParam = if (isRegexParam) rpVal.substring(1, rpVal.length - 1) else rpVal.lowercase(Locale.ROOT)
            if (cleanParam.isBlank() || cleanParam.length > 80) return
            if (isRegexParam) {
                try { Regex(cleanParam) } catch (_: Exception) { return }
            }
            val cleanPat = patternPart.trim().lowercase(Locale.ROOT)
            builder.addRemoveParamRule(
                RemoveParamRule(
                    pattern = if (cleanPat == "*" || cleanPat == "/*") "" else cleanPat,
                    param = cleanParam,
                    isRegexParam = isRegexParam,
                    includeDomains = includeDomains,
                    excludeDomains = excludeDomains,
                )
            )
            return
        }

        // Skip document-only blocking rules (we never want an ad filter rule to blank a top-level page)
        if (!isException && includeTypes == TYPE_DOCUMENT) return

        // FAST PATH 1: Pure domain rule (||example.com^ or ||example.com^$third-party or ||example.com^$popup)
        val pureDomain = extractPureDomain(patternPart)
        if (pureDomain != null &&
            includeDomains.isEmpty() &&
            excludeDomains.isEmpty() &&
            excludeTypes == 0 &&
            redirect == null &&
            !important
        ) {
            if (isException) {
                if (includeTypes == 0 && !firstPartyOnly) {
                    if (thirdPartyOnly) {
                        builder.thirdPartyExceptionDomains.add(pureDomain)
                    } else {
                        builder.exceptionDomains.add(pureDomain)
                    }
                    return
                }
            } else {
                if (isPopup && includeTypes == TYPE_POPUP && !firstPartyOnly) {
                    builder.popupDomains.add(pureDomain)
                    return
                }
                if (includeTypes == 0 && !firstPartyOnly) {
                    if (thirdPartyOnly) {
                        if (isTrackerList) builder.thirdPartyTrackerDomains.add(pureDomain)
                        else builder.thirdPartyAdDomains.add(pureDomain)
                    } else {
                        if (isTrackerList) builder.trackerDomains.add(pureDomain)
                        else builder.adDomains.add(pureDomain)
                    }
                    if (isPopup) builder.popupDomains.add(pureDomain)
                    return
                }
            }
        }

        // STRUCTURED RULE PATH: path rules, wildcards, static ad images/GIFs, regex, $domain, $image/$script/$xhr, @@ exceptions, $redirect
        val isRegex = patternPart.length > 2 && patternPart.startsWith("/") && patternPart.endsWith("/")
        if (isRegex) {
            val expr = patternPart.substring(1, patternPart.length - 1)
            if (expr.length !in 4..140) return
            // Validate regex syntax once on JVM so JS RegExp compilation never throws
            try {
                Regex(expr)
            } catch (_: Exception) {
                return
            }
            builder.addNetworkRule(
                CompiledNetworkRule(
                    pattern = expr,
                    anchorDomain = null,
                    isRegex = true,
                    isException = isException,
                    isTracker = isTrackerList,
                    thirdPartyOnly = thirdPartyOnly,
                    firstPartyOnly = firstPartyOnly,
                    includeTypes = includeTypes,
                    excludeTypes = excludeTypes,
                    includeDomains = includeDomains,
                    excludeDomains = excludeDomains,
                    isPopup = isPopup,
                    important = important,
                    redirect = redirect,
                )
            )
            return
        }

        val cleanPat = patternPart.trim()
        if (cleanPat.length < 4 || cleanPat.length > 180) return
        // Avoid overly generic wildcard-only patterns like `*` or `/*`
        if (cleanPat.replace("*", "").replace("^", "").replace("|", "").length < 4) return

        val anchorDomain = extractAnchorDomain(cleanPat)

        builder.addNetworkRule(
            CompiledNetworkRule(
                pattern = cleanPat.lowercase(Locale.ROOT),
                anchorDomain = anchorDomain,
                isRegex = false,
                isException = isException,
                isTracker = isTrackerList,
                thirdPartyOnly = thirdPartyOnly,
                firstPartyOnly = firstPartyOnly,
                includeTypes = includeTypes,
                excludeTypes = excludeTypes,
                includeDomains = includeDomains,
                excludeDomains = excludeDomains,
                isPopup = isPopup,
                important = important,
                redirect = redirect,
            )
        )
    }

    private fun extractPureDomain(patternPart: String): String? {
        if (!patternPart.startsWith("||")) return null
        val rest = patternPart.substring(2)
        if (rest.contains('*') || rest.contains('?')) return null
        val cleaned = when {
            rest.endsWith("^") -> rest.dropLast(1)
            !rest.contains('/') && !rest.contains('^') -> rest
            else -> return null
        }.lowercase(Locale.ROOT)
        if (cleaned.contains('/') || cleaned.contains('^')) return null
        return if (isValidDomain(cleaned)) cleaned else null
    }

    private fun extractAnchorDomain(patternPart: String): String? {
        if (!patternPart.startsWith("||")) return null
        val rest = patternPart.substring(2)
        val endIdx = rest.indexOfFirst { it == '^' || it == '/' || it == '*' || it == '?' || it == ':' }
        val candidate = (if (endIdx > 0) rest.substring(0, endIdx) else rest).lowercase(Locale.ROOT)
        return if (isValidDomain(candidate)) candidate else null
    }

    private fun mapResourceType(opt: String): Int = when (opt) {
        "script" -> TYPE_SCRIPT
        "image", "img" -> TYPE_IMAGE
        "stylesheet", "css" -> TYPE_STYLESHEET
        "subdocument", "frame" -> TYPE_SUBDOCUMENT
        "xmlhttprequest", "xhr", "fetch" -> TYPE_XHR
        "media" -> TYPE_MEDIA
        "font" -> TYPE_FONT
        "ping", "beacon" -> TYPE_PING
        "object", "object-subrequest" -> TYPE_OBJECT
        "other", "websocket", "csp_report" -> TYPE_OTHER
        "document", "doc", "main_frame" -> TYPE_DOCUMENT
        "popup" -> TYPE_POPUP
        else -> 0
    }

    private fun mapRedirectTarget(target: String): String? = when {
        target.contains("noopjs") || target.contains("noop.js") || target.contains("empty.js") ||
            target.contains("googlesyndication") || target.contains("googletagmanager") ||
            target.contains("google-analytics") || target.contains("gpt.js") ||
            target.contains("hd-main") || target.contains("bab-defuser") -> "js"
        target.contains("1x1.gif") || target.contains("2x2.png") || target.contains("3x2.png") ||
            target.contains("32x32.png") || target.contains("noop.png") || target.contains("noop.gif") -> "gif"
        target.contains("noopframe") || target.contains("noop.html") -> "html"
        target.contains("noopcss") || target.contains("noop.css") -> "css"
        target.contains("nooptext") || target.contains("noop.txt") || target == "empty" -> "empty"
        else -> null
    }

    fun isValidDomain(dom: String): Boolean {
        if (dom.length < 4 || dom.length > 253 || !dom.contains('.')) return false
        if (dom == "localhost" || dom == "local" || dom.startsWith(".") || dom.endsWith(".")) return false
        for (ch in dom) {
            if (!(ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '-')) return false
        }
        return true
    }
}
