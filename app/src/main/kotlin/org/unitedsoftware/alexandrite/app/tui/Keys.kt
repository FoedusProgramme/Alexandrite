package org.unitedsoftware.alexandrite.app.tui

/** A key the person pressed. */
internal sealed interface Key {
    data object Up : Key

    data object Down : Key

    data object Left : Key

    data object Right : Key

    data object Enter : Key

    data object Space : Key

    data object Tab : Key

    data object Backspace : Key

    /** Ctrl-U, which empties an input. */
    data object Clear : Key

    data object Escape : Key

    /** Ctrl-C. */
    data object Interrupt : Key

    /** The end of the input, or Ctrl-D. */
    data object End : Key

    data class Typed(val text: String) : Key
}

/** How long the rest of an escape sequence may take to arrive after its ESC, in milliseconds. */
private const val SEQUENCE_WAIT = 50L

/**
 * Decodes the keys of a terminal in raw mode from [read], which returns the next character or -1 at the end, and
 * [readWithin], which returns one within the given milliseconds or a negative number when none arrives.
 */
internal class KeyDecoder(private val read: () -> Int, private val readWithin: (Long) -> Int) {
    fun key(): Key {
        while (true) {
            val char = read()
            decoded(char)?.let { return it }
        }
    }

    private fun decoded(char: Int): Key? = when (char) {
        -1, 4 -> Key.End
        3 -> Key.Interrupt
        '\r'.code, '\n'.code -> Key.Enter
        8, 127 -> Key.Backspace
        9 -> Key.Tab
        21 -> Key.Clear
        ' '.code -> Key.Space
        27 -> escaped()
        in 0..31 -> null
        else -> typed(char)
    }

    private fun typed(char: Int): Key {
        if (!Character.isHighSurrogate(char.toChar())) return Key.Typed(char.toChar().toString())
        val low = read()
        return if (low >= 0 && Character.isLowSurrogate(low.toChar())) {
            Key.Typed(String(charArrayOf(char.toChar(), low.toChar())))
        } else {
            Key.Typed(char.toChar().toString())
        }
    }

    /** The key of an escape sequence, [Key.Escape] for a lone ESC, null for a sequence that means nothing here. */
    private fun escaped(): Key? {
        val next = readWithin(SEQUENCE_WAIT)
        if (next < 0) return Key.Escape
        if (next != '['.code && next != 'O'.code) return null
        var final: Int
        do {
            final = readWithin(SEQUENCE_WAIT)
        } while (final in '0'.code..'9'.code || final == ';'.code)
        return when (final) {
            'A'.code -> Key.Up
            'B'.code -> Key.Down
            'C'.code -> Key.Right
            'D'.code -> Key.Left
            'Z'.code -> Key.Up
            else -> null
        }
    }
}
