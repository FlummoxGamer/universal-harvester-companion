package app.harvester.companion.settings.trustpanel.store

import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

data class CertificateDetails(
    val subjectOrganization: String? = null,
    val subjectLocality: String? = null,
    val subjectCountry: String? = null,
    val subjectCommonName: String? = null,
    val issuerOrganization: String? = null,
    val issuerCommonName: String? = null,
)

internal fun X509Certificate.toCertificateDetails(): CertificateDetails {
    val subject = parseDistinguishedName(subjectX500Principal.getName(X500Principal.RFC2253))
    val issuer = parseDistinguishedName(issuerX500Principal.getName(X500Principal.RFC2253))

    return CertificateDetails(
        subjectOrganization = subject["O"],
        subjectLocality = subject["L"],
        subjectCountry = subject["C"],
        subjectCommonName = subject["CN"],
        issuerOrganization = issuer["O"],
        issuerCommonName = issuer["CN"],
    )
}

internal fun parseDistinguishedName(distinguishedName: String): Map<String, String> {
    val attributes = mutableMapOf<String, String>()
    var type: String? = null
    val value = StringBuilder()
    var isEscaped = false

    fun addAttribute() {
        val text = value.trim().toString()
        type?.let { if (text.isNotEmpty()) attributes.putIfAbsent(it.trim().uppercase(), text) }
        type = null
        value.clear()
    }

    for (char in distinguishedName) {
        when {
            isEscaped -> {
                value.append(char)
                isEscaped = false
            }
            char == '\\' -> isEscaped = true
            char == '=' && type == null -> {
                type = value.toString()
                value.clear()
            }
            char == ',' || char == '+' -> addAttribute()
            else -> value.append(char)
        }
    }
    addAttribute()

    return attributes
}
