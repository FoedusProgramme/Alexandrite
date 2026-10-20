package org.unitedsoftware.alexandrite.tools.exec

import org.unitedsoftware.alexandrite.sdk.tool.SegmentJoint

/** A step of a chain: the command [words], run after the step before it as [joint] says. */
internal class ChainStep(
    val words: List<String>,
    val joint: SegmentJoint?,
    val redirects: List<Redirect> = emptyList(),
    /** The words with an unquoted `*` or `?`, which a shell passes as they are only while they match no file. */
    val patterns: List<String> = emptyList(),
) {
    /** Whether the step changes the directory of the steps after it. */
    val changesDirectory: Boolean get() = words.first() == CD
}

/** A redirection that only joins a step's output streams, or ties one of its streams to `/dev/null`. */
internal enum class Redirect(
    /** As a shell writes it. */
    val text: String,
) {
    ERRORS_TO_OUTPUT("2>&1"),
    OUTPUT_TO_ERRORS(">&2"),
    OUTPUT_DISCARDED(">/dev/null"),
    ERRORS_DISCARDED("2>/dev/null"),
    BOTH_DISCARDED("&>/dev/null"),
    NO_INPUT("</dev/null"),
}

/** The steps that a POSIX shell's `-c` script runs, which stop at a failure that ends a list when [errexit]. */
internal class Chain(val steps: List<ChainStep>, val errexit: Boolean)

/**
 * The chain that [argv] runs in a POSIX shell with `-c`, null when [argv] runs no shell so, or the script uses more
 * than words, quotes without expansions, `&&`, `||`, `;`, `|`, line breaks, `cd <dir>`, [Redirect]s and, but in zsh,
 * the wildcards `*` and `?`.
 */
internal fun shellChain(argv: List<String>, home: String?): Chain? {
    val program = argv.first().substringAfterLast('/')
    val (shell, arguments) = when {
        program in POSIX_SHELLS -> program to argv.drop(1)
        program == "busybox" && argv.getOrNull(1) == "sh" -> "sh" to argv.drop(2)
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
                    val steps = ShellScript.parse(arguments[i], home, shell) ?: return null
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
    /**
     * The steps of [script] as [shell] reads it, where a leading `~` of a word is [home], null when it uses anything
     * beyond a chain.
     */
    fun parse(script: String, home: String?, shell: String = "sh"): List<ChainStep>? {
        val zsh = shell == "zsh"
        val steps = mutableListOf<ChainStep>()
        var words = mutableListOf<String>()
        var redirects = mutableListOf<Redirect>()
        var patterns = mutableListOf<String>()
        val current = StringBuilder()
        var inWord = false
        var quoted = false
        var wild = false
        var joint: SegmentJoint? = null
        var open = false

        fun endWord() {
            if (inWord) {
                words += current.toString()
                if (wild) patterns += current.toString()
            }
            current.setLength(0)
            inWord = false
            quoted = false
            wild = false
        }

        fun endStep(next: SegmentJoint) {
            steps += ChainStep(words, joint, redirects, patterns)
            words = mutableListOf()
            redirects = mutableListOf()
            patterns = mutableListOf()
            joint = next
            open = next != SegmentJoint.THEN
        }

        /** The descriptor that the digits before a redirection name, `-` for several, null for none; a word ends. */
        fun descriptor(): Char? {
            if (quoted || current.isEmpty() || !current.all(Char::isDigit)) {
                endWord()
                return null
            }
            val fd = current.singleOrNull() ?: '-'
            current.setLength(0)
            inWord = false
            return fd
        }

        var i = 0
        while (i < script.length) {
            val c = script[i]
            when {
                c == ' ' || c == '\t' -> endWord()

                c == '\n' -> {
                    endWord()
                    if (words.isNotEmpty()) {
                        endStep(SegmentJoint.THEN)
                    } else if (redirects.isNotEmpty()) {
                        return null
                    }
                }

                c == ';' -> {
                    endWord()
                    if (words.isEmpty()) return null
                    endStep(SegmentJoint.THEN)
                }

                c == '|' -> {
                    endWord()
                    if (words.isEmpty() || script.getOrNull(i + 1) == '&') return null
                    if (script.getOrNull(i + 1) == '|') {
                        endStep(SegmentJoint.OR)
                        i++
                    } else {
                        endStep(SegmentJoint.PIPE)
                    }
                }

                c == '&' -> {
                    endWord()
                    when (script.getOrNull(i + 1)) {
                        '&' -> {
                            if (words.isEmpty()) return null
                            endStep(SegmentJoint.AND)
                            i++
                        }

                        '>' -> {
                            if (shell != "bash" && !zsh) return null
                            redirects += Redirect.BOTH_DISCARDED
                            i = (devNull(script, i + 2) ?: return null) - 1
                        }

                        else -> return null
                    }
                }

                c == '>' || c == '<' -> {
                    val (redirect, end) = redirection(script, i, descriptor()) ?: return null
                    redirects += redirect
                    i = end - 1
                }

                c == '\'' -> {
                    val end = script.indexOf('\'', i + 1)
                    if (end < 0) return null
                    current.append(script, i + 1, end)
                    inWord = true
                    quoted = true
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
                    quoted = true
                }

                c == '\\' -> {
                    if (i + 1 >= script.length) return null
                    if (script[i + 1] != '\n') {
                        current.append(script[i + 1])
                        inWord = true
                        quoted = true
                    }
                    i++
                }

                c == '~' && !inWord -> {
                    if (home == null || !(i + 1 == script.length || script[i + 1] in WORD_END)) return null
                    current.append(home)
                    inWord = true
                }

                (c == '#' || c == '=') && !inWord -> return null

                c == '*' || c == '?' -> {
                    if (zsh) return null
                    current.append(c)
                    inWord = true
                    wild = true
                }

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
        } else if (open || redirects.isNotEmpty()) {
            return null
        }
        if (steps.isEmpty()) return null
        for ((index, step) in steps.withIndex()) {
            val piped = steps.getOrNull(index + 1)?.joint == SegmentJoint.PIPE
            if (!plain(step.words)) return null
            if (step.changesDirectory &&
                (piped || step.joint == SegmentJoint.PIPE || step.redirects.isNotEmpty() || step.patterns.isNotEmpty())
            ) {
                return null
            }
            if (zsh && !redirectedOnce(step, piped)) return null
        }
        return steps
    }

    /** Whether [words] run a program, or change the directory to the one they name. */
    private fun plain(words: List<String>): Boolean {
        val first = words.first()
        if (first == CD) return words.size == 2 && !words[1].startsWith("-") && words[1].isNotEmpty()
        return first !in KEYWORDS && first !in BUILTINS && !ASSIGNMENT.matches(first)
    }

    /** Whether each stream of [step], whose output is [piped] on, goes one way at most, as zsh tees it otherwise. */
    private fun redirectedOnce(step: ChainStep, piped: Boolean): Boolean {
        val output = step.redirects.count { it in OUTPUT_REDIRECTS } + if (piped) 1 else 0
        val errors = step.redirects.count { it in ERRORS_REDIRECTS }
        val input = step.redirects.count { it == Redirect.NO_INPUT } + if (step.joint == SegmentJoint.PIPE) 1 else 0
        return output <= 1 && errors <= 1 && input <= 1
    }
}

/**
 * The [Redirect] whose `>` or `<` is at [at] after the descriptor [fd], with the index after it, null for any other
 * redirection.
 */
private fun redirection(script: String, at: Int, fd: Char?): Pair<Redirect, Int>? {
    if (script[at] == '<') {
        if (fd != null && fd != '0') return null
        return devNull(script, at + 1)?.let { Redirect.NO_INPUT to it }
    }
    if (script.getOrNull(at + 1) == '&') {
        val target = blank(script, at + 2)
        if (!ends(script, target + 1)) return null
        val redirect = when ("${fd ?: '1'}${script.getOrNull(target)}") {
            "21" -> Redirect.ERRORS_TO_OUTPUT
            "12" -> Redirect.OUTPUT_TO_ERRORS
            else -> return null
        }
        return redirect to target + 1
    }
    val redirect = when (fd) {
        null, '1' -> Redirect.OUTPUT_DISCARDED
        '2' -> Redirect.ERRORS_DISCARDED
        else -> return null
    }
    return devNull(script, at + 1)?.let { redirect to it }
}

/** The index after a `/dev/null` that [script] holds after the blanks from [from], null when it holds none there. */
private fun devNull(script: String, from: Int): Int? {
    val start = blank(script, from)
    val end = start + DEV_NULL.length
    return end.takeIf { script.startsWith(DEV_NULL, start) && ends(script, end) }
}

/** The index of the first character from [from] in [script] that is no space or tab. */
private fun blank(script: String, from: Int): Int {
    var index = from
    while (index < script.length && (script[index] == ' ' || script[index] == '\t')) index++
    return index
}

/** Whether a redirection's target ends at [at] in [script]. */
private fun ends(script: String, at: Int): Boolean = at >= script.length || script[at] in " \t\n;&|"

private const val CD = "cd"

private const val DEV_NULL = "/dev/null"

/** The shells whose `-c` scripts command.exec can run itself. */
private val POSIX_SHELLS = setOf("sh", "bash", "dash", "zsh", "ksh", "mksh")

private val SHORT = Regex("-(?:[eux]+c?|c)")

private val HARMLESS_LONG = setOf("--norc", "--noprofile")

/** Characters that only a shell acts on outside quotes. */
private const val SPECIAL = "\$`<>(){}[]!"

/** The characters after which a leading `~` stands for the home directory. */
private const val WORD_END = "/ \t\n;&|"

private val OUTPUT_REDIRECTS = setOf(Redirect.OUTPUT_TO_ERRORS, Redirect.OUTPUT_DISCARDED, Redirect.BOTH_DISCARDED)

private val ERRORS_REDIRECTS = setOf(Redirect.ERRORS_TO_OUTPUT, Redirect.ERRORS_DISCARDED, Redirect.BOTH_DISCARDED)

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
