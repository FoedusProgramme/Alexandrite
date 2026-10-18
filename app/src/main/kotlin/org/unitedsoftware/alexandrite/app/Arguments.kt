package org.unitedsoftware.alexandrite.app

import org.unitedsoftware.alexandrite.app.control.ControlRequest
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** The locations given on the command line. */
internal class Options(val configFile: Path? = null, val dataDir: Path? = null, val cacheDir: Path? = null)

/** What the command line asks for. */
internal sealed interface Command {
    class Run(val options: Options) : Command

    /** A request to the app that runs with the same data directory. */
    class Control(val options: Options, val request: ControlRequest) : Command

    /** The [command] of the command table, which [action] carries out. */
    class Act(val options: Options, val command: CliCommand, val action: CliAction.Act) : Command

    /** The usage of [command], or of every command when it is null. */
    data class Help(val command: CliCommand? = null) : Command

    data object Version : Command

    /** Bad arguments, given to [command] when it is not null. */
    class Invalid(val message: String, val command: CliCommand? = null) : Command
}

/** What a command of the command table does once its arguments are read. */
internal sealed interface CliAction {
    /** Runs the app in the foreground. */
    data object Foreground : CliAction

    /** Asks the running app and prints its answer. */
    class Ask(val request: ControlRequest) : CliAction

    fun interface Act : CliAction {
        /** Returns the exit code. */
        fun run(cli: Cli): Int
    }
}

/** Thrown by a command that cannot read its arguments. */
internal class UsageException(message: String) : Exception(message)

/** The arguments a command was given: its [operands], flags and options. */
internal class Given(
    val operands: List<String>,
    private val flags: Set<String>,
    private val options: Map<String, List<String>>,
) {
    fun flag(name: String): Boolean = name in flags

    /** The value of the option [name], the last one when it is given more than once. */
    fun option(name: String): String? = options[name]?.lastOrNull()

    fun all(name: String): List<String> = options[name].orEmpty()

    /** The only operand, or a [UsageException] saying the command [what] it needs. */
    fun single(what: String): String = operands.singleOrNull() ?: throw UsageException(what)

    /** Throws [message] when there are more operands than [count]. */
    fun atMost(count: Int, message: String) {
        if (operands.size > count) throw UsageException(message)
    }
}

/** A command of the command table, named by [words] and read by [read]. */
internal class CliCommand(
    val words: List<String>,
    /** What follows the words in the usage line. */
    val synopsis: String,
    /** The group in the overview. */
    val area: String,
    val summary: String,
    /** What the command's own help tells beyond [summary]. */
    val help: String = "",
    val flags: Set<String> = emptySet(),
    val options: Set<String> = emptySet(),
    val read: (Given) -> CliAction,
) {
    val name: String get() = words.joinToString(" ")

    val usage: String get() = "alexandrite $name${if (synopsis.isEmpty()) "" else " $synopsis"}"
}

private const val CONFIG = "--config"
private const val DATA_DIR = "--data-dir"
private const val CACHE_DIR = "--cache-dir"
private const val VERSION = "--version"
private const val HELP = "--help"
private const val END = "--"

/** Where the summaries start in the usage's list of commands. */
private const val COLUMN = 30

internal fun parseArguments(args: List<String>, commands: List<CliCommand> = COMMANDS): Command {
    val paths = mutableMapOf<String, Path>()
    val rest = mutableListOf<String>()
    var index = 0
    while (index < args.size) {
        val arg = args[index++]
        if (arg == END) {
            rest += args.subList(index - 1, args.size)
            break
        }
        val option = arg.substringBefore('=')
        if (option == CONFIG || option == DATA_DIR || option == CACHE_DIR) {
            val value = if ('=' in arg) arg.substringAfter('=') else args.getOrNull(index++)
            if (value.isNullOrEmpty() || value.startsWith("--")) return Command.Invalid("$option needs a value.")
            if (option in paths) return Command.Invalid("$option is given more than once.")
            paths[option] = try {
                Path.of(value)
            } catch (e: InvalidPathException) {
                return Command.Invalid("$option names no valid path: ${e.reason}.")
            }
        } else {
            rest += arg
        }
    }
    val options = Options(paths[CONFIG], paths[DATA_DIR], paths[CACHE_DIR])
    if (rest.isEmpty() || rest.first().startsWith("-")) {
        rest.firstOrNull { it != HELP && it != VERSION }?.let { return Command.Invalid("Unknown option $it.") }
        return when {
            HELP in rest -> Command.Help()
            VERSION in rest -> Command.Version
            else -> Command.Run(options)
        }
    }
    val words = rest.takeWhile { !it.startsWith("-") }
    val command = commands.filter { words.take(it.words.size) == it.words }.maxByOrNull { it.words.size }
        ?: return unknown(words, commands)
    val given = given(command, rest.drop(command.words.size)) ?: return Command.Help(command)
    val action = try {
        command.read(given.getOrElse { return Command.Invalid(it.message.orEmpty(), command) })
    } catch (e: UsageException) {
        return Command.Invalid(e.message.orEmpty(), command)
    }
    return when (action) {
        CliAction.Foreground -> Command.Run(options)
        is CliAction.Ask -> Command.Control(options, action.request)
        is CliAction.Act -> Command.Act(options, command, action)
    }
}

/** What [arguments] give [command], null when they ask for its help. */
private fun given(command: CliCommand, arguments: List<String>): Result<Given>? {
    val operands = mutableListOf<String>()
    val flags = mutableSetOf<String>()
    val options = mutableMapOf<String, MutableList<String>>()
    var index = 0
    while (index < arguments.size) {
        val arg = arguments[index++]
        val name = arg.substringBefore('=')
        when {
            arg == END -> {
                operands += arguments.drop(index)
                break
            }

            arg == HELP -> return null

            arg in command.flags -> flags += arg

            name in command.options -> {
                val value = if ('=' in arg) arg.substringAfter('=') else arguments.getOrNull(index++)
                if (value == null || value.startsWith("--")) return failure("$name needs a value.")
                options.getOrPut(name, ::mutableListOf) += value
            }

            arg.startsWith("-") && arg != "-" && arg.getOrNull(1)?.isDigit() != true ->
                return failure("Unknown option $arg.")

            else -> operands += arg
        }
    }
    return Result.success(Given(operands, flags, options))
}

private fun failure(message: String): Result<Given> = Result.failure(UsageException(message))

private fun unknown(words: List<String>, commands: List<CliCommand>): Command {
    val group = commands.filter { it.words.size > 1 && it.words.first() == words.first() }
    if (group.isEmpty()) return Command.Invalid("Unknown command ${words.first()}.")
    val subcommands = group.map { it.words[1] }.distinct().joinToString(", ")
    val given = words.getOrNull(1)
    return if (given == null) {
        Command.Invalid("${words.first()} needs one of $subcommands.")
    } else {
        Command.Invalid("Unknown command ${words.first()} $given: ${words.first()} takes $subcommands.")
    }
}

/** The usage of every command, with the default locations [defaults]. */
internal fun usage(defaults: Locations, commands: List<CliCommand> = COMMANDS): String = buildString {
    appendLine("Usage: alexandrite [$CONFIG <file>] [$DATA_DIR <dir>] [$CACHE_DIR <dir>] [$VERSION] [$HELP]")
    appendLine("       alexandrite <command> [<argument>...] [$HELP]")
    appendLine()
    appendLine("Runs Alexandrite until it receives SIGTERM or SIGINT, or carries out a command.")
    for ((area, members) in commands.groupBy { it.area }) {
        appendLine()
        appendLine("$area:")
        for (command in members) {
            val line = "${command.name} ${command.synopsis}".trimEnd()
            if (line.length < COLUMN) {
                appendLine("  ${line.padEnd(COLUMN)}${command.summary}")
            } else {
                appendLine("  $line")
                appendLine("  ${"".padEnd(COLUMN)}${command.summary}")
            }
        }
    }
    appendLine()
    append(
        """
        Options:
          $CONFIG <file>     the config file, by default ${defaults.configFile}
          $DATA_DIR <dir>    the data directory, by default ${defaults.dataDir}
          $CACHE_DIR <dir>   the cache directory, by default ${defaults.cacheDir}
          $VERSION           print the version and exit
          $HELP              print this help, or a command's own after it, and exit

        Each default comes from ALEXANDRITE_CONFIG, ALEXANDRITE_DATA_DIR and ALEXANDRITE_CACHE_DIR when set,
        else from XDG_CONFIG_HOME, XDG_DATA_HOME and XDG_CACHE_HOME, else from the platform's own locations.
        The cache is the data directory's "cache" directory when only the data directory is named.
        Commands that change the config go through the Alexandrite that runs with the same data directory,
        or write the file themselves when none runs.
        """.trimIndent(),
    )
}

/** The usage of [command]. */
internal fun usage(command: CliCommand): String = buildString {
    appendLine("Usage: ${command.usage}")
    appendLine()
    append(command.summary.replaceFirstChar(Char::uppercaseChar)).append('.')
    if (command.help.isNotEmpty()) append("\n\n").append(command.help)
}
