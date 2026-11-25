package app.harvester.companion.privacy

import java.net.IDN
import java.net.URI
import java.util.Locale

/**
 * Detects suspicious Punycode (xn--) and mixed-script IDN homograph spoofing domains
 * (e.g., Cyrillic/Greek lookalike characters posing as Latin brand names).
 */
object UhHomographDetector {

    private val CONFUSABLE_SCRIPTS = setOf(
        Character.UnicodeScript.CYRILLIC,
        Character.UnicodeScript.GREEK,
        Character.UnicodeScript.CHEROKEE,
        Character.UnicodeScript.ARMENIAN,
    )

    fun inspectUrl(urlStr: String): HomographWarning? {
        if (urlStr.isBlank() || (!urlStr.startsWith("http://") && !urlStr.startsWith("https://"))) {
            return null
        }
        val rawHost = try {
            URI(urlStr).host?.lowercase(Locale.ROOT)?.trim()?.removeSuffix(".") ?: return null
        } catch (_: Exception) {
            return null
        }
        if (rawHost.isBlank()) return null

        val asciiHost = try {
            IDN.toASCII(rawHost).lowercase(Locale.ROOT)
        } catch (_: Exception) {
            rawHost
        }
        val unicodeHost = try {
            IDN.toUnicode(asciiHost).lowercase(Locale.ROOT)
        } catch (_: Exception) {
            rawHost
        }

        val hasPunycode = asciiHost.split('.').any { it.startsWith("xn--") }
        val hasNonAscii = unicodeHost.any { it.code > 127 }
        if (!hasPunycode && !hasNonAscii) return null

        val labels = unicodeHost.split('.')
        for (label in labels) {
            if (label.isEmpty()) continue
            var hasLatin = false
            var hasConfusable = false
            var hasOtherNonLatin = false
            var confusableCount = 0

            var i = 0
            while (i < label.length) {
                val cp = label.codePointAt(i)
                i += Character.charCount(cp)
                if (cp in '0'.code..'9'.code || cp == '-'.code) continue
                val script = Character.UnicodeScript.of(cp)
                when {
                    script == Character.UnicodeScript.LATIN -> hasLatin = true
                    script in CONFUSABLE_SCRIPTS -> {
                        hasConfusable = true
                        confusableCount++
                    }
                    script != Character.UnicodeScript.COMMON && script != Character.UnicodeScript.INHERITED -> {
                        hasOtherNonLatin = true
                    }
                }
            }

            // 1) Mixed Latin + Cyrillic/Greek/Cherokee/Armenian inside the same label (classic spoof)
            if (hasLatin && hasConfusable) {
                return HomographWarning(
                    asciiHost = asciiHost,
                    unicodeHost = unicodeHost,
                    reason = "Mixed lookalike characters detected in domain name",
                )
            }

            // 2) Whole-label confusable Cyrillic/Greek/Cherokee under a Latin TLD (.com/.net/.org/.io/.co)
            val tld = labels.lastOrNull().orEmpty()
            val latinTld = tld in setOf("com", "net", "org", "io", "co", "app", "dev", "gov", "edu", "bank", "pay")
            if (latinTld && hasConfusable && !hasOtherNonLatin && confusableCount >= 2 && label != tld) {
                return HomographWarning(
                    asciiHost = asciiHost,
                    unicodeHost = unicodeHost,
                    reason = "Internationalized lookalike domain ($asciiHost)",
                )
            }
        }
        return null
    }
}
