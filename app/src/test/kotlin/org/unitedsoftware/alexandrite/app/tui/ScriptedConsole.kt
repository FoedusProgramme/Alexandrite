package org.unitedsoftware.alexandrite.app.tui

/**
 * A console of [width] columns whose keys come from [keys] in order, then [Key.End], and whose output lands on a
 * [screen] that keeps the lines it shows; [frames] holds the screen each time a key is read.
 */
internal class ScriptedConsole(
    keys: List<Key> = emptyList(),
    override val width: Int = 72,
    override val height: Int = 24,
) : Console {
    val keys = ArrayDeque(keys)
    val screen = VirtualScreen()
    val frames = mutableListOf<List<String>>()
    val written = StringBuilder()
    var closed = false

    override fun write(text: String) {
        synchronized(screen) {
            written.append(text)
            screen.write(text)
        }
    }

    override fun key(): Key {
        frames += screen.lines()
        return keys.removeFirstOrNull() ?: Key.End
    }

    override fun <T> raw(block: () -> T): T = block()

    override fun close() {
        closed = true
    }
}

/** Keys that type [text]. */
internal fun typed(text: String): List<Key> = text.map { if (it == ' ') Key.Space else Key.Typed("$it") }

/** The lines a terminal shows after the output given to [write], for the cursor moves and erasures prompts use. */
internal class VirtualScreen {
    private val rows = mutableListOf(StringBuilder())
    private var row = 0
    private var column = 0

    fun write(text: String) {
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '\u001B' -> index = escape(text, index)

                char == '\r' -> {
                    column = 0
                    index++
                }

                char == '\n' -> {
                    row++
                    column = 0
                    while (rows.size <= row) rows += StringBuilder()
                    index++
                }

                else -> {
                    val line = rows[row]
                    while (line.length < column) line.append(' ')
                    if (column < line.length) line.setCharAt(column, char) else line.append(char)
                    column++
                    index++
                }
            }
        }
    }

    /** Applies the escape sequence at [start] and returns the index after it. */
    private fun escape(text: String, start: Int): Int {
        var end = start + 2
        while (end < text.length && !text[end].isLetter()) end++
        val parameters = text.substring(start + 2, end)
        when (text.getOrNull(end)) {
            'A' -> row = (row - (parameters.toIntOrNull() ?: 1)).coerceAtLeast(0)

            'J' -> {
                rows[row].setLength(minOf(column, rows[row].length))
                while (rows.size > row + 1) rows.removeAt(rows.size - 1)
            }

            'K' -> rows[row].setLength(if (parameters == "2") 0 else minOf(column, rows[row].length))
        }
        return end + 1
    }

    /** The lines shown, without the empty one the cursor waits on at the end. */
    fun lines(): List<String> = rows.map { "$it".trimEnd() }.dropLastWhile { it.isEmpty() }
}
