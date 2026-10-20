package org.unitedsoftware.alexandrite.tools.exec

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A program that wrappers would run: [argv] with [environment] beside the usual variables, stopped after [timeout]. */
internal class Unwrapped(val argv: List<String>, val environment: Map<String, String>, val timeout: Duration?)

/**
 * [argv] without the wrappers it starts with, such as `env A=1` or `timeout 5`, or null when a wrapper takes an option
 * that this does not know or wraps nothing.
 */
internal fun unwrap(argv: List<String>): Unwrapped? {
    var rest = argv
    val environment = LinkedHashMap<String, String>()
    var timeout: Duration? = null
    while (rest.isNotEmpty()) {
        val arguments = rest.drop(1)
        val inner = when (rest.first().substringAfterLast('/')) {
            "env" -> {
                val assignments = arguments.takeWhile { ASSIGNMENT.matches(it) }
                for (assignment in assignments) {
                    environment[assignment.substringBefore('=')] =
                        assignment.substringAfter('=')
                }
                arguments.drop(assignments.size).takeUnless { it.firstOrNull()?.startsWith("-") == true }
            }

            "timeout" -> {
                val (limit, command) = timeoutCommand(arguments) ?: return null
                if (limit != null) timeout = minOf(timeout ?: limit, limit)
                command
            }

            "nice" -> options(arguments, flags = "", valued = "n", longValued = setOf("adjustment"), numeric = true)

            "nohup" -> arguments.takeUnless { it.firstOrNull()?.startsWith("-") == true }

            "time" -> options(arguments, flags = "p")

            "stdbuf" -> options(arguments, flags = "", valued = "ioe", longValued = setOf("input", "output", "error"))

            "ionice" -> options(
                arguments,
                flags = "t",
                valued = "cn",
                longValued = setOf("class", "classdata"),
                longFlags = setOf("ignore"),
            )

            "caffeinate" -> options(arguments, flags = "dimsu", valued = "t")

            else -> return Unwrapped(rest, environment, timeout)
        }
        if (inner.isNullOrEmpty()) return null
        rest = inner
    }
    return null
}

/** The command after the options of a wrapper, null when an option is not among the ones given or nothing follows. */
private fun options(
    arguments: List<String>,
    flags: String,
    valued: String = "",
    longValued: Set<String> = emptySet(),
    longFlags: Set<String> = emptySet(),
    numeric: Boolean = false,
): List<String>? {
    var i = 0
    while (i < arguments.size) {
        val argument = arguments[i]
        when {
            argument == "--" -> return arguments.drop(i + 1)

            !argument.startsWith("-") || argument == "-" -> return arguments.drop(i)

            argument.startsWith("--") -> {
                val name = argument.substring(2).substringBefore('=')
                when {
                    name in longFlags && '=' !in argument -> Unit
                    name in longValued && '=' in argument -> Unit
                    name in longValued && i + 1 < arguments.size -> i++
                    else -> return null
                }
            }

            numeric && argument.drop(1).all(Char::isDigit) -> Unit

            else -> {
                var j = 1
                while (j < argument.length) {
                    val option = argument[j++]
                    when (option) {
                        in flags -> Unit

                        in valued -> {
                            if (j == argument.length) {
                                if (++i >= arguments.size) return null
                            }
                            j = argument.length
                        }

                        else -> return null
                    }
                }
            }
        }
        i++
    }
    return null
}

/** The limit and the command of `timeout`'s [arguments], a null limit for none, null when they cannot be read. */
private fun timeoutCommand(arguments: List<String>): Pair<Duration?, List<String>>? {
    var i = 0
    while (i < arguments.size && arguments[i].startsWith("-")) {
        val argument = arguments[i]
        when {
            argument == "--" -> {
                i++
                break
            }

            argument in TIMEOUT_FLAGS -> Unit

            argument == "-s" || argument == "-k" || argument == "--signal" || argument == "--kill-after" -> i++

            argument.startsWith("--signal=") || argument.startsWith("--kill-after=") -> Unit

            argument.length > 2 && (argument.startsWith("-s") || argument.startsWith("-k")) -> Unit

            else -> return null
        }
        i++
    }
    val limit = arguments.getOrNull(i)?.let(::duration) ?: return null
    val command = arguments.drop(i + 1).ifEmpty { return null }
    return limit.takeIf { it.isPositive() } to command
}

/** `timeout`'s [text], such as `5`, `1.5m` or `2h`. */
private fun duration(text: String): Duration? {
    val match = DURATION.matchEntire(text) ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null
    return when (match.groupValues[2]) {
        "", "s" -> value.seconds
        "m" -> value.minutes
        "h" -> value.hours
        else -> value.days
    }
}

private val ASSIGNMENT = Regex("[A-Za-z_][A-Za-z0-9_]*=.*", RegexOption.DOT_MATCHES_ALL)

private val DURATION = Regex("([0-9]+(?:\\.[0-9]+)?)([smhd]?)")

private val TIMEOUT_FLAGS = setOf("--foreground", "--preserve-status", "-v", "--verbose")
