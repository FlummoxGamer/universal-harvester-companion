package app.harvester.companion.settings.addons

data class FormattedPermission(
    val rawKey: String,
    val title: String,
    val isSensitive: Boolean = false,
)

object ExtensionPermissionsFormatter {

    private val apiDescriptions = mapOf(
        "<all_urls>" to ("Access your data for all websites" to true),
        "*://*/*" to ("Access your data for all websites" to true),
        "http://*/*" to ("Access your data for all HTTP websites" to true),
        "https://*/*" to ("Access your data for all HTTPS websites" to true),
        "tabs" to ("Access browser tabs and open URLs" to true),
        "activeTab" to ("Access the currently active tab when clicked" to false),
        "webRequest" to ("Inspect network requests as pages load" to true),
        "webRequestBlocking" to ("Block or modify network requests" to true),
        "declarativeNetRequest" to ("Filter and block network requests" to false),
        "declarativeNetRequestFeedback" to ("Read matched network filter rules" to false),
        "cookies" to ("Read and modify site cookies" to true),
        "history" to ("Access browsing history" to true),
        "bookmarks" to ("Read and modify bookmarks" to false),
        "downloads" to ("Download files and manage download history" to false),
        "downloads.open" to ("Open files downloaded to your device" to true),
        "clipboardRead" to ("Read data from the clipboard" to true),
        "clipboardWrite" to ("Copy data to the clipboard" to false),
        "storage" to ("Store extension settings and local data" to false),
        "unlimitedStorage" to ("Store unlimited local extension data" to false),
        "notifications" to ("Display system notifications" to false),
        "privacy" to ("Read and modify browser privacy settings" to true),
        "proxy" to ("Control browser proxy settings" to true),
        "nativeMessaging" to ("Exchange messages with native app components" to true),
        "browsingData" to ("Clear recent browsing history, cookies, and cache" to true),
        "sessions" to ("Access recently closed tabs" to false),
        "topSites" to ("Access most visited sites" to false),
        "webNavigation" to ("Monitor page navigation events" to false),
        "contextMenus" to ("Add items to browser menus" to false),
        "menus" to ("Add items to browser menus" to false),
        "alarms" to ("Schedule background tasks" to false),
        "scripting" to ("Inject scripts and styles into web pages" to true),
        "find" to ("Search text across web pages" to false),
        "geolocation" to ("Access your device location" to true),
        "management" to ("Monitor installed extensions" to true),
        "dns" to ("Resolve domain names via DNS" to false),
        "theme" to ("Read and adjust browser theme properties" to false),
        "userScripts" to ("Register custom page scripts" to true),
    )

    private val dataCollectionDescriptions = mapOf(
        "technicalAndInteraction" to "Collects technical and interaction telemetry",
        "locationInfo" to "Collects location information",
        "healthInfo" to "Collects health information",
        "financialAndPaymentInfo" to "Collects financial or payment information",
        "authenticationInfo" to "Collects authentication credentials",
        "personalCommunications" to "Collects personal communications",
        "personallyIdentifyingInfo" to "Collects personally identifying information",
        "browsingActivity" to "Collects browsing activity",
        "websiteContent" to "Collects website content",
        "websiteActivity" to "Collects website activity",
        "bookmarksInfo" to "Collects bookmarks data",
        "searchTerms" to "Collects search queries",
    )

    fun formatPermissions(
        permissions: List<String>,
        origins: List<String> = emptyList(),
    ): List<FormattedPermission> {
        val result = linkedMapOf<String, FormattedPermission>()

        val allEntries = permissions + origins
        var hasAllSites = false
        val specificHosts = linkedSetOf<String>()

        for (raw in allEntries) {
            val key = raw.trim()
            if (key.isBlank()) continue

            if (key == "<all_urls>" || key == "*://*/*" || key == "http://*/*" || key == "https://*/*") {
                hasAllSites = true
                continue
            }

            val known = apiDescriptions[key]
            if (known != null) {
                result[key] = FormattedPermission(key, known.first, known.second)
            } else if (key.contains("://")) {
                val host = extractReadableHost(key)
                if (host != null) {
                    specificHosts.add(host)
                }
            } else {
                result[key] = FormattedPermission(
                    rawKey = key,
                    title = formatFallbackApiName(key),
                    isSensitive = false
                )
            }
        }

        val ordered = mutableListOf<FormattedPermission>()
        if (hasAllSites) {
            ordered.add(
                FormattedPermission(
                    rawKey = "<all_urls>",
                    title = "Access your data for all websites",
                    isSensitive = true
                )
            )
        } else if (specificHosts.isNotEmpty()) {
            when {
                specificHosts.size <= 3 -> {
                    specificHosts.forEach { host ->
                        ordered.add(
                            FormattedPermission(
                                rawKey = host,
                                title = "Access your data for $host",
                                isSensitive = true
                            )
                        )
                    }
                }
                else -> {
                    val preview = specificHosts.take(2).joinToString(", ")
                    val remaining = specificHosts.size - 2
                    ordered.add(
                        FormattedPermission(
                            rawKey = "hosts_multi",
                            title = "Access your data on $preview and $remaining more sites",
                            isSensitive = true
                        )
                    )
                }
            }
        }

        ordered.addAll(result.values.sortedByDescending { it.isSensitive })
        return ordered
    }

    fun formatDataCollection(dataCollectionPermissions: List<String>): List<String> {
        return dataCollectionPermissions
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "none" }
            .distinct()
            .map { key ->
                dataCollectionDescriptions[key] ?: "Data collection: ${formatFallbackApiName(key)}"
            }
    }

    private fun extractReadableHost(pattern: String): String? {
        val afterScheme = pattern.substringAfter("://", "")
        if (afterScheme.isBlank()) return null
        val hostPart = afterScheme.substringBefore("/").trim()
        if (hostPart.isBlank() || hostPart == "*") return null
        return hostPart.removePrefix("*.")
    }

    private fun formatFallbackApiName(raw: String): String {
        val spaced = raw
            .replace('.', ' ')
            .replace('_', ' ')
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .trim()
        return spaced.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
