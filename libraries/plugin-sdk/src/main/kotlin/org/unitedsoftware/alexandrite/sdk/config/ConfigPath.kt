package org.unitedsoftware.alexandrite.sdk.config

/**
 * The paths of the config file: keys joined by dots, a key that holds a dot or a bracket written in brackets and quotes,
 * as `providers.openai-responses.endpoints.main.models["gpt-4.1"].contextWindow`.
 */
public object ConfigPath {
    /** The keys of [path], or throws [IllegalArgumentException] saying what is malformed. */
    public fun parse(path: String): List<String> = Reader(path).keys()

    /** The path of [keys], which [parse] reads back. */
    public fun format(keys: List<String>): String = keys.fold("", ::join)

    /** [path] followed by [key]. */
    public fun join(path: String, key: String): String = when {
        !isBare(key) -> "$path[${quoted(key)}]"
        path.isEmpty() -> key
        else -> "$path.$key"
    }

    private fun isBare(key: String): Boolean = key.isNotEmpty() && key.none { it in SPECIAL }

    private fun quoted(key: String): String = key.replace("\\", "\\\\").replace("\"", "\\\"").let { "\"$it\"" }

    private const val SPECIAL = ".[]"

    private const val ESCAPED = "\"'\\"

    private class Reader(private val path: String) {
        private var index = 0

        fun keys(): List<String> {
            if (path.isEmpty()) malformed("it is empty")
            val keys = mutableListOf<String>()
            while (true) {
                keys += if (peek() == '[') bracketed() else bare()
                when (peek()) {
                    null -> return keys
                    '.' -> index++
                    '[' -> Unit
                    else -> malformed("'${peek()}' at ${index + 1} follows a key: join keys by dots")
                }
            }
        }

        private fun bare(): String {
            val start = index
            while (index < path.length && path[index] !in SPECIAL) index++
            if (index == start) malformed("a key is missing at ${start + 1}")
            return path.substring(start, index)
        }

        private fun bracketed(): String {
            val open = index++
            val quote = peek()
            if (quote != '"' && quote != '\'') {
                malformed("the key in the bracket at ${open + 1} needs quotes, as [\"gpt-4.1\"]")
            }
            index++
            val key = StringBuilder()
            while (true) {
                val char = peek() ?: malformed("the quote at ${open + 2} is not closed")
                index++
                when (char) {
                    quote -> break

                    '\\' -> {
                        val escaped = peek()?.takeIf { it in ESCAPED }
                            ?: malformed("only \\\", \\' and \\\\ escape a character in a quoted key")
                        key.append(escaped)
                        index++
                    }

                    else -> key.append(char)
                }
            }
            if (peek() != ']') malformed("the bracket at ${open + 1} is not closed after its quoted key")
            index++
            if (key.isEmpty()) malformed("the key in the bracket at ${open + 1} is empty")
            return key.toString()
        }

        private fun peek(): Char? = path.getOrNull(index)

        private fun malformed(why: String): Nothing =
            throw IllegalArgumentException("Malformed config path '$path': $why.")
    }
}
