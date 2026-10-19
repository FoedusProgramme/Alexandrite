package org.unitedsoftware.alexandrite.internal.text

/**
 * [text] on one line, cut to [max] characters around the first place where one of [words] occurs, ignoring case, with
 * `…` where it was cut.
 */
public fun snippet(text: String, words: List<String>, max: Int): String {
    val flat = text.replace(WHITESPACE, " ").trim()
    if (flat.length <= max) return flat
    val window = (max - 2 * ELLIPSIS.length).coerceAtLeast(1)
    val at = words.filter { it.isNotEmpty() }.mapNotNull { word ->
        flat.indexOf(word, ignoreCase = true).takeIf { it >= 0 }
    }.minOrNull() ?: 0
    var start = (at - window / 4).coerceIn(0, flat.length - window)
    var end = start + window
    if (start > 0 && flat[start].isLowSurrogate()) start++
    if (end < flat.length && flat[end - 1].isHighSurrogate()) end--
    return buildString {
        if (start > 0) append(ELLIPSIS)
        append(flat, start, end)
        if (end < flat.length) append(ELLIPSIS)
    }
}

private val WHITESPACE = Regex("\\s+")

private const val ELLIPSIS = "…"
