package org.foedusprogramme.alexandrite.agent.permission

import org.foedusprogramme.alexandrite.sdk.tool.CallReview
import org.foedusprogramme.alexandrite.sdk.tool.ReviewPreview
import org.foedusprogramme.alexandrite.sdk.tool.RuleSubject
import org.foedusprogramme.alexandrite.sdk.tool.ToolNames

/** An allow or deny rule of the config: every call of [tool], or the calls whose argv starts with [prefix]. */
internal class RuleEntry private constructor(
    /** The rule as the config writes it. */
    val text: String,
    val tool: String,
    val prefix: List<String>,
    /** [prefix] as the config writes it. */
    private val written: String,
) {
    /** What the rule covers, as people are shown it. */
    val subject: RuleSubject get() = RuleSubject(tool, prefix, written.ifEmpty { tool })

    /**
     * Whether the rule as a deny rule refuses a call of the tool [name] that [review] describes: a prefix names the
     * program as the call writes it or as the file name of its real path, and covers a call that shows no command line.
     */
    fun denies(name: String, review: CallReview): Boolean {
        if (name != tool) return false
        if (prefix.isEmpty()) return true
        val command = review.preview as? ReviewPreview.Command ?: return true
        return startsWith(command.argv, command.executable?.fileName?.toString())
    }

    /**
     * Whether the rule as an allow rule lets a call of the tool [name] that [review] describes run: a prefix names the
     * program exactly as the call writes it.
     */
    fun allows(name: String, review: CallReview): Boolean {
        if (name != tool) return false
        if (prefix.isEmpty()) return true
        val command = review.preview as? ReviewPreview.Command ?: return false
        return startsWith(command.argv, null)
    }

    /** Whether the rule as an allow rule lets every call that [subject] covers run. */
    fun allows(subject: RuleSubject): Boolean =
        subject.tool == tool && (prefix.isEmpty() || startsWith(subject.tokens, null))

    /** Whether [argv] starts with [prefix], with its program named as the prefix names it or with the file name [file]. */
    private fun startsWith(argv: List<String>, file: String?): Boolean = argv.size >= prefix.size &&
        (argv[0] == prefix[0] || file == prefix[0]) &&
        argv.subList(1, prefix.size) == prefix.subList(1, prefix.size)

    override fun toString(): String = text

    companion object {
        /** Why [text] is no rule, null when it is one. */
        fun problem(text: String): String? = try {
            read(text, null)
            null
        } catch (e: EntryProblem) {
            e.message
        }

        /** The rule [text] writes, where a word's leading `~` is [home]. */
        fun parse(text: String, home: String?): RuleEntry = try {
            read(text, home)
        } catch (e: EntryProblem) {
            throw IllegalArgumentException("'$text' ${e.message}", e)
        }

        private fun read(text: String, home: String?): RuleEntry {
            val trimmed = text.trim()
            val open = trimmed.indexOf('(')
            val tool = (if (open < 0) trimmed else trimmed.substring(0, open)).trim()
            if (!ToolNames.PATTERN.matches(tool) || (open >= 0 && !trimmed.endsWith(")"))) {
                throw EntryProblem(
                    "is no rule: write a tool name, or command.exec with the start of a command line in " +
                        "parentheses, such as \"command.exec(git push)\"",
                )
            }
            if (open < 0) return RuleEntry(trimmed, tool, emptyList(), "")
            if (tool != EXEC) {
                throw EntryProblem("gives an argument prefix to a tool other than command.exec: write the tool alone")
            }
            val written = trimmed.substring(open + 1, trimmed.length - 1).trim()
            val words = split(written, home)
            if (words.isEmpty()) throw EntryProblem("has nothing in its parentheses: write the tool alone")
            return RuleEntry(trimmed, tool, words, written)
        }
    }
}

private class EntryProblem(override val message: String) : Exception(message)

/** [line] split at whitespace outside quotes, with quotes, backslash escapes and a word's leading `~` as [home]. */
private fun split(line: String, home: String?): List<String> {
    val words = mutableListOf<String>()
    val current = StringBuilder()
    var inWord = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '\n' || c == '\r' || c in OPERATORS || isSubstitution(line, i) -> throw EntryProblem(SHELL_SYNTAX)

            c.isWhitespace() -> {
                if (inWord) {
                    words += current.toString()
                    current.setLength(0)
                    inWord = false
                }
            }

            c == '\'' -> {
                val end = line.indexOf('\'', i + 1)
                if (end < 0) throw EntryProblem(UNCLOSED)
                current.append(line, i + 1, end)
                inWord = true
                i = end
            }

            c == '"' -> {
                i++
                while (i < line.length && line[i] != '"') {
                    if (isSubstitution(line, i)) throw EntryProblem(SHELL_SYNTAX)
                    if (line[i] == '\\' && i + 1 < line.length && line[i + 1] in "\"\\$`") i++
                    current.append(line[i])
                    i++
                }
                if (i >= line.length) throw EntryProblem(UNCLOSED)
                inWord = true
            }

            c == '~' && !inWord && home != null &&
                (i + 1 == line.length || line[i + 1] == '/' || line[i + 1].isWhitespace()) -> {
                current.append(home)
                inWord = true
            }

            c == '\\' -> {
                if (i + 1 >= line.length) throw EntryProblem("ends its parentheses with a lone backslash")
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

private fun isSubstitution(line: String, index: Int): Boolean =
    line[index] == '`' || (line[index] == '$' && line.getOrNull(index + 1) == '(')

private const val EXEC = "command.exec"

private const val OPERATORS = "|&;<>"

private const val SHELL_SYNTAX = "holds shell syntax, which no call of command.exec runs: write a program and its " +
    "first arguments"

private const val UNCLOSED = "has a quote that is never closed"
