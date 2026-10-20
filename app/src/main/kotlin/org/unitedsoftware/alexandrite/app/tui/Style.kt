package org.unitedsoftware.alexandrite.app.tui

/** The colours and emphasis of terminal output, none when [colors] is false. */
internal class Style(val colors: Boolean) {
    private fun sgr(code: String, text: String): String {
        if (!colors || text.isEmpty()) return text
        return "\u001B[${code}m$text\u001B[0m"
    }

    fun bold(text: String): String = sgr("1", text)

    fun dim(text: String): String = sgr("2", text)

    fun inverse(text: String): String = sgr("7", text)

    fun struck(text: String): String = sgr("9", text)

    fun red(text: String): String = sgr("31", text)

    fun green(text: String): String = sgr("32", text)

    fun yellow(text: String): String = sgr("33", text)

    fun cyan(text: String): String = sgr("36", text)

    fun gray(text: String): String = sgr("90", text)

    /** How an input shows where the next character goes. */
    val cursor: String get() = if (colors) inverse(" ") else "_"

    /** The glyph of [mark], coloured. */
    fun mark(mark: Mark): String = when (mark) {
        Mark.OK -> green(Glyph.OK)
        Mark.FAIL -> red(Glyph.FAIL)
        Mark.WARN -> yellow(Glyph.WARN)
        Mark.INFO -> gray(Glyph.INFO)
        Mark.SKIP -> gray(Glyph.SKIP)
    }
}

/** How a result came out. */
internal enum class Mark { OK, FAIL, WARN, INFO, SKIP }

internal object Glyph {
    const val TOP = "┌"
    const val BAR = "│"
    const val BOTTOM = "└"
    const val TEE = "├"
    const val ACTIVE = "◆"
    const val ANSWERED = "◇"
    const val STEP = "◈"
    const val CANCELLED = "■"
    const val INVALID = "▲"
    const val OK = "✓"
    const val FAIL = "✗"
    const val WARN = "!"
    const val INFO = "·"
    const val SKIP = "-"
    const val ON = "●"
    const val OFF = "○"
    const val PICKED = "◼"
    const val UNPICKED = "◻"
    const val POINTER = "›"
    const val MASK = "▪"
    val SPINNER = listOf("◒", "◐", "◓", "◑")
}
