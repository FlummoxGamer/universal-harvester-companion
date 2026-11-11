package app.harvester.companion.utils

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
