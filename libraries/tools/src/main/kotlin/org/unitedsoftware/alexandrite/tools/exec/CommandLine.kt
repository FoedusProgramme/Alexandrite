package org.unitedsoftware.alexandrite.tools.exec

import org.unitedsoftware.alexandrite.internal.tool.ToolRefusal

/** Splits a command line into words like a shell, without running one. */
internal object CommandLine {
    /**
     * [line] split at whitespace outside quotes, with single and double quotes and backslash escapes, a leading
     * unquoted `~` expanded to [home] and nothing else expanded.
     *
     * Throws a [ToolRefusal] for shell syntax that only a shell would act on.
     */
    fun split(line: String, home: String?): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        var inWord = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\n' || c == '\r' -> throw shellSyntax("a line break")

                c.isWhitespace() -> {
                    if (inWord) {
                        words += current.toString()
                        current.setLength(0)
                        inWord = false
                    }
                }

                c in OPERATORS -> {
                    val doubled = i + 1 < line.length && line[i + 1] == c && c != ';' && c != '<'
                    throw shellSyntax(if (doubled) "'$c$c'" else "'$c'")
                }

                c == '`' -> throw shellSyntax("'`'")

                c == '$' && line.getOrNull(i + 1) == '(' -> throw shellSyntax("'$('")

                c == '\'' -> {
                    val end = line.indexOf('\'', i + 1)
                    if (end < 0) throw ToolRefusal(UNTERMINATED)
                    current.append(line, i + 1, end)
                    inWord = true
                    i = end
                }

                c == '"' -> {
                    i++
                    while (i < line.length && line[i] != '"') {
                        when {
                            line[i] == '\\' && i + 1 < line.length && line[i + 1] in "\"\\$`" -> i++
                            line[i] == '`' -> throw shellSyntax("'`'")
                            line[i] == '$' && line.getOrNull(i + 1) == '(' -> throw shellSyntax("'$('")
                        }
                        current.append(line[i])
                        i++
                    }
                    if (i >= line.length) throw ToolRefusal(UNTERMINATED)
                    inWord = true
                }

                c == '~' && !inWord && home != null &&
                    (i + 1 == line.length || line[i + 1] == '/' || line[i + 1].isWhitespace()) -> {
                    current.append(home)
                    inWord = true
                }

                c == '\\' -> {
                    if (i + 1 >= line.length) throw ToolRefusal("The command ends with a lone backslash.")
                    current.append(line[i + 1])
                    inWord = true
                    i++
                }

                else -> {
                    current.append(c)
                    inWord = true
                }
            }
            i++
        }
        if (inWord) words += current.toString()
        return words
    }

    private fun shellSyntax(token: String): ToolRefusal = ToolRefusal(
        "The command holds $token, which is shell syntax, but no shell runs it: run one program and set 'cwd' " +
            "instead of using cd; for pipes or redirects pass sh -c '…' explicitly.",
    )

    private const val OPERATORS = "|&;<>"

    private const val UNTERMINATED = "The command has a quote that is never closed."
}
