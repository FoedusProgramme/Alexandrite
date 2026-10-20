package org.unitedsoftware.alexandrite.tools.exec

import org.unitedsoftware.alexandrite.sdk.tool.SegmentJoint

/** A step of a chain: the command [words], run after the step before it as [joint] says. */
internal class ChainStep(val words: List<String>, val joint: SegmentJoint?) {
    /** Whether the step changes the directory of the steps after it. */
    val changesDirectory: Boolean get() = words.first() == CD
}

/** The steps that a POSIX shell's `-c` script runs, which stop at a failure that ends a list when [errexit]. */
internal class Chain(val steps: List<ChainStep>, val errexit: Boolean)

/**
 * The chain that [argv] runs in a POSIX shell with `-c`, null when [argv] runs no shell so, or the script uses more
 * than words, quotes without expansions, `&&`, `||`, `;`, line breaks and `cd <dir>`.
 */
internal fun shellChain(argv: List<String>, home: String?): Chain? {
    val program = baseName(argv.first())
    val arguments = when {
        program in POSIX_SHELLS -> argv.drop(1)
        program == "busybox" && argv.getOrNull(1) == "sh" -> argv.drop(2)
        else -> return null
    }
    var errexit = false
    var i = 0
    while (i < arguments.size) {
        val argument = arguments[i++]
        when {
            argument in HARMLESS_LONG -> Unit

            SHORT.matches(argument) -> {
                if ('e' in argument) errexit = true
                if (argument.endsWith('c')) {
                    if (i != arguments.size - 1) return null
                    val steps = ShellScript.parse(arguments[i], home) ?: return null
                    return Chain(steps, errexit)
                }
            }

            else -> return null
        }
    }
    return null
}

/** Reads the shell scripts that command.exec runs itself, step by step. */
internal object ShellScript {
    /** The steps of [script], where a leading `~` of a word is [home], null when it uses anything beyond a chain. */
    fun parse(script: String, home: String?): List<ChainStep>? {
        val steps = mutableListOf<ChainStep>()
        var words = mutableListOf<String>()
        val current = StringBuilder()
        var inWord = false
        var joint: SegmentJoint? = null
        var open = false

        fun endWord() {
            if (inWord) words += current.toString()
            current.setLength(0)
            inWord = false
        }

        fun endStep(next: SegmentJoint) {
            steps += ChainStep(words, joint)
            words = mutableListOf()
            joint = next
            open = next != SegmentJoint.THEN
        }

        var i = 0
        while (i < script.length) {
            val c = script[i]
            when {
                c == ' ' || c == '\t' -> endWord()

                c == '\n' -> {
                    endWord()
                    if (words.isNotEmpty()) endStep(SegmentJoint.THEN)
                }

                c == ';' -> {
                    endWord()
                    if (words.isEmpty()) return null
                    endStep(SegmentJoint.THEN)
                }

                c == '&' || c == '|' -> {
                    endWord()
                    if (script.getOrNull(i + 1) != c || words.isEmpty()) return null
                    endStep(if (c == '&') SegmentJoint.AND else SegmentJoint.OR)
                    i++
                }

                c == '\'' -> {
                    val end = script.indexOf('\'', i + 1)
                    if (end < 0) return null
                    current.append(script, i + 1, end)
                    inWord = true
                    i = end
                }

                c == '"' -> {
                    i++
                    while (i < script.length && script[i] != '"') {
                        val inner = script[i]
                        when {
                            inner == '$' || inner == '`' -> return null

                            inner == '\\' && i + 1 < script.length && script[i + 1] in "\"\\$`\n" -> {
                                if (script[i + 1] != '\n') current.append(script[i + 1])
                                i++
                            }

                            else -> current.append(inner)
                        }
                        i++
                    }
                    if (i >= script.length) return null
                    inWord = true
                }

                c == '\\' -> {
                    if (i + 1 >= script.length) return null
                    if (script[i + 1] != '\n') {
                        current.append(script[i + 1])
                        inWord = true
                    }
                    i++
                }

                c == '~' && !inWord -> {
                    if (home == null || !(i + 1 == script.length || script[i + 1] in WORD_END)) return null
                    current.append(home)
                    inWord = true
                }

                (c == '#' || c == '=') && !inWord -> return null

                c in SPECIAL -> return null

                else -> {
                    current.append(c)
                    inWord = true
                }
            }
            i++
        }
        endWord()
        if (words.isNotEmpty()) {
            endStep(SegmentJoint.THEN)
        } else if (open) {
            return null
        }
        if (steps.isEmpty() || steps.any { !plain(it.words) }) return null
        return steps
    }

    /** Whether [words] run a program, or change the directory to the one they name. */
    private fun plain(words: List<String>): Boolean {
        val first = words.first()
        if (first == CD) return words.size == 2 && !words[1].startsWith("-") && words[1].isNotEmpty()
        return first !in KEYWORDS && first !in BUILTINS && !ASSIGNMENT.matches(first)
    }
}

private const val CD = "cd"

/** The shells whose `-c` scripts command.exec can run itself. */
private val POSIX_SHELLS = setOf("sh", "bash", "dash", "zsh", "ksh", "mksh")

private val SHORT = Regex("-(?:[eux]+c?|c)")

private val HARMLESS_LONG = setOf("--norc", "--noprofile")

/** Characters that only a shell acts on outside quotes. */
private const val SPECIAL = "\$`<>(){}*?[]!"

/** The characters after which a leading `~` stands for the home directory. */
private const val WORD_END = "/ \t\n;&|"

private val ASSIGNMENT = Regex("[A-Za-z_][A-Za-z0-9_]*=.*", RegexOption.DOT_MATCHES_ALL)

private val KEYWORDS = setOf(
    "if", "then", "else", "elif", "fi", "case", "esac", "for", "select", "while", "until", "do", "done",
    "function", "in", "coproc", "[[", "]]", "{", "}", "!",
)

/** Commands that a shell runs itself, whose effect a plain program of their name would not have. */
private val BUILTINS = setOf(
    "export", "set", "unset", "source", ".", "eval", "exec", "alias", "unalias", "readonly", "local", "declare",
    "typeset", "shift", "trap", "ulimit", "umask", "return", "exit", "read", "pushd", "popd", "dirs", "builtin",
    "let", "shopt", "setopt", "unsetopt", "hash", "wait", "jobs", "fg", "bg", "disown", "getopts", "history", "fc",
    "enable", "bind", "complete", "compgen", "caller", "logout", "suspend", "times", "autoload", "emulate",
    "zmodload", "rehash", "noglob", "nocorrect", "command", "type", "functions", "unfunction", "print", "whence",
    "where", "bindkey", "zstyle",
)
