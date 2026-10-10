package org.foedusprogramme.alexandrite.agent.prompt

/** Neutralizes the lines of untrusted text that would start one of the [reserved] headers. */
internal class Escaper(reserved: List<String>) {
    private val lineStart = Regex(
        "(?m)^(?=[\\h\\p{Cf}]*+(?i:${reserved.joinToString("|") { Regex.escape(it) }}))",
    )

    /** [text] with a backslash in front of each line that would start a reserved header. */
    fun text(text: String): String = lineStart.replace(text) { "\\" }

    companion object {
        /** The headers that only Alexandrite writes. */
        val HEADERS: Escaper = Escaper(listOf("[alexandrite:", "[/alexandrite:"))
    }
}

/** [text] on one line, each run of line breaks a space. */
internal fun oneLine(text: String): String = text.replace(LINE_BREAKS, " ")

/** The lines of [text], split at every kind of line break. */
internal fun lines(text: String): List<String> = text.split(LINE_BREAK)

private val LINE_BREAK = Regex("\\R")

private val LINE_BREAKS = Regex("\\R+")
