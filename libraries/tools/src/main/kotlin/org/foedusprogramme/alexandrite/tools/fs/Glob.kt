package org.foedusprogramme.alexandrite.tools.fs

/**
 * A glob over `/`-separated relative paths: `*` matches within a segment, `**` any number of whole segments, `?` one
 * character, `[abc]` and `[!abc]` one character of a set, `{a,b}` either alternative, and `\` quotes the next character.
 */
internal class Glob private constructor(private val regex: Regex) {
    fun matches(relative: String): Boolean = regex.matches(relative)

    companion object {
        /** [pattern] compiled, refused when it is blank or absolute. */
        fun of(pattern: String, name: String): Glob {
            if (pattern.isBlank()) throw Refusal("Give '$name' as a non-empty glob pattern.")
            if (pattern.startsWith("/") || pattern.startsWith("~")) {
                throw Refusal(
                    "'$name' is relative to the directory searched: give that directory as 'path' and the rest as " +
                        "'$name'.",
                )
            }
            return Glob(Regex(translate(pattern.removePrefix("./"))))
        }
    }
}

private fun translate(glob: String): String {
    val regex = StringBuilder()
    var braces = 0
    var i = 0
    while (i < glob.length) {
        val char = glob[i]
        val segmentStart = i == 0 || glob[i - 1] == '/' || (braces > 0 && glob[i - 1] in "{,")
        val segmentEnd = i + 2 >= glob.length || (braces > 0 && glob[i + 2] in ",}")
        when {
            char == '\\' && i + 1 < glob.length -> {
                regex.append(Regex.escape(glob[i + 1].toString()))
                i += 2
                continue
            }

            glob.startsWith("**/", i) && segmentStart -> {
                regex.append("(?:.*/)?")
                i += 3
                continue
            }

            glob.startsWith("**", i) && segmentStart && segmentEnd -> {
                regex.append(".*")
                i += 2
                continue
            }

            char == '*' -> regex.append("[^/]*")

            char == '?' -> regex.append("[^/]")

            char == '[' && classEnd(glob, i) > 0 -> {
                val end = classEnd(glob, i)
                regex.append(characterClass(glob.substring(i + 1, end)))
                i = end + 1
                continue
            }

            char == '{' && braceEnd(glob, i) > 0 -> {
                regex.append("(?:")
                braces++
            }

            char == ',' && braces > 0 -> regex.append('|')

            char == '}' && braces > 0 -> {
                regex.append(')')
                braces--
            }

            else -> regex.append(Regex.escape(char.toString()))
        }
        i++
    }
    return regex.toString()
}

/** The index of the `]` that closes the class opened at [start], or -1. */
private fun classEnd(glob: String, start: Int): Int {
    var i = start + 1
    if (i < glob.length && glob[i] == '!') i++
    if (i < glob.length && glob[i] == ']') i++
    while (i < glob.length && glob[i] != ']' && glob[i] != '/') i++
    return if (i < glob.length && glob[i] == ']') i else -1
}

/** The index of the `}` that closes the alternatives opened at [start], or -1. */
private fun braceEnd(glob: String, start: Int): Int {
    var depth = 0
    for (i in start until glob.length) {
        when (glob[i]) {
            '{' -> depth++
            '}' -> if (--depth == 0) return i
        }
    }
    return -1
}

/** The class of the characters in [content] (`!` first negates it), never `/`. */
private fun characterClass(content: String): String {
    val negated = content.startsWith("!")
    val members = content.removePrefix("!").map { char ->
        if (char == '-' || char.isLetterOrDigit()) char.toString() else "\\" + char
    }
    val set = members.joinToString("")
    return if (negated) "[[^$set]&&[^/]]" else "[$set&&[^/]]"
}
