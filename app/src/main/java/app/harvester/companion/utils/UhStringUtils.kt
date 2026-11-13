package app.harvester.companion.utils

private const val REPLACEMENT_CHAR = '\uFFFD'

fun String.toWellFormed(): String {
    var result: StringBuilder? = null
    var i = 0
    while (i < length) {
        val c = this[i]
        if (i + 1 < length && Character.isSurrogatePair(c, this[i + 1])) {
            result?.append(c)?.append(this[i + 1])
            i += 2
            continue
        }
        if (c.isSurrogate()) {
            if (result == null) {
                result = StringBuilder(length).append(this, 0, i)
            }
            result.append(REPLACEMENT_CHAR)
        } else {
            result?.append(c)
        }
        i++
    }
    return result?.toString() ?: this
}

fun String.truncateMiddle(maxLength: Int, ellipsis: String = "…"): String {
    if (length <= maxLength) {
        return this
    }
    if (maxLength <= ellipsis.length) {
        return take(maxLength)
    }
    val half = (maxLength - ellipsis.length) / 2
    val start = take(half)
    val end = takeLast(maxLength - ellipsis.length - half)
    return "$start$ellipsis$end"
}
