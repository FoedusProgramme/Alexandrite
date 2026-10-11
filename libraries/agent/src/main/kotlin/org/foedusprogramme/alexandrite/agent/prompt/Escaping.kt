package org.foedusprogramme.alexandrite.agent.prompt

/** Neutralizes the lines of untrusted text that would start one of the [reserved] headers. */
internal class Escaper(reserved: List<String>) {
    private val header = Regex("^$INVISIBLE*+(?i:${reserved.joinToString("|") { Regex.escape(it) }})")

    /** [text] with a backslash in front of each line that would start a reserved header. */
    fun text(text: String): String = buildString(text.length) {
        var start = 0
        for (lineBreak in LINE_BREAK.findAll(text)) {
            line(text.substring(start, lineBreak.range.first))
            append(lineBreak.value)
            start = lineBreak.range.last + 1
        }
        line(text.substring(start))
    }

    private fun StringBuilder.line(line: String) {
        if (header.containsMatchIn(line)) append('\\')
        append(line)
    }

    companion object {
        /** The headers that only Alexandrite writes. */
        val HEADERS: Escaper = Escaper(listOf("[alexandrite:", "[/alexandrite:", PROJECT_HEADER))
    }
}

/** How the header of a trusted folder's project instructions starts. */
internal const val PROJECT_HEADER: String = "Project instructions from"

/** A code point that a line may start with unseen. */
internal const val INVISIBLE: String = "[\\h\\p{Zs}\\p{Cc}\\p{Cf}\\p{Mn}\\p{Me}\\u115F\\u1160\\u3164\\uFFA0\\u2800]"

/** [text] on one line, each run of line breaks a space. */
internal fun oneLine(text: String): String = text.replace(LINE_BREAKS, " ")

/** The lines of [text], split at every kind of line break. */
internal fun lines(text: String): List<String> = text.split(LINE_BREAK)

private val LINE_BREAK = Regex("\\R")

private val LINE_BREAKS = Regex("\\R+")
