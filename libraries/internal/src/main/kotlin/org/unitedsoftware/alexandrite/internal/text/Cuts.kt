package org.unitedsoftware.alexandrite.internal.text

/** How much of [text] fits [max] characters without splitting a surrogate pair. */
public fun fittingLength(text: CharSequence, max: Int): Int = when {
    text.length <= max -> text.length
    max > 0 && text[max - 1].isHighSurrogate() -> max - 1
    else -> max.coerceAtLeast(0)
}

/** Where the last [max] characters of [text] start, moved forward past the second half of a split surrogate pair. */
public fun tailStart(text: CharSequence, max: Int): Int {
    if (text.length <= max) return 0
    val start = text.length - max.coerceAtLeast(0)
    return if (start < text.length && text[start].isLowSurrogate()) start + 1 else start
}

/** The head of [text] that fits [max] characters, a surrogate pair kept whole. */
public fun cut(text: String, max: Int): String = text.substring(0, fittingLength(text, max))

/** [text], or its head and `…` in [max] characters where it is longer, a surrogate pair kept whole. */
public fun cutWithEllipsis(text: String, max: Int): String {
    if (text.length <= max) return text
    return text.substring(0, fittingLength(text, max - ELLIPSIS.length)) + ELLIPSIS
}

/** [text], or `…` and its tail in [max] characters where it is longer, a surrogate pair kept whole. */
public fun cutStartWithEllipsis(text: String, max: Int): String {
    if (text.length <= max) return text
    return ELLIPSIS + text.substring(tailStart(text, max - ELLIPSIS.length))
}

private const val ELLIPSIS = "…"
