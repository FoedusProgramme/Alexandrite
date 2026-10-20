package org.unitedsoftware.alexandrite.app

import org.unitedsoftware.alexandrite.app.control.ControlRequest
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** The locations given on the command line. */
internal class Options(
    val configFile: Path? = null,
    val dataDir: Path? = null,
    val cacheDir: Path? = null,
    /** Whether `--plain` asks for plain lines. */
    val plain: Boolean = false,
)

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

/** Thrown by a command that cannot read its arguments, saying why in [text]. */
internal class UsageException(val text: Text) : Exception(text.key) {
    constructor(key: String, vararg args: Pair<String, Any?>) : this(Text(key, *args))
}

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
    fun single(what: Text): String = operands.singleOrNull() ?: throw UsageException(what)

    /** Throws [message] when there are more operands than [count]. */
    fun atMost(count: Int, message: Text) {
        if (operands.size > count) throw UsageException(message)
    }
}

/** A command of the command table, named by [words] and read by [read]. */
internal class CliCommand(
    val words: List<String>,
    /** What follows the words in the usage line. */
    val synopsis: String,
    /** The text key of the group in the overview. */
    val area: String,
    val flags: Set<String> = emptySet(),
    val options: Set<String> = emptySet(),
    val read: (Given) -> CliAction,
) {
    val name: String get() = words.joinToString(" ")

    val usage: String get() = "alexandrite $name${if (synopsis.isEmpty()) "" else " $synopsis"}"

    /** The text key of its summary, and with `.help` of what its own help tells beyond it. */
    val textKey: String get() = "command.${words.joinToString(".")}"
}

private const val CONFIG = "--config"
private const val DATA_DIR = "--data-dir"
private const val CACHE_DIR = "--cache-dir"
private const val VERSION = "--version"
private const val HELP = "--help"
private const val PLAIN = "--plain"
private const val END = "--"

/** Where the summaries start in the usage's list of commands. */
private const val COLUMN = 30

internal fun parseArguments(
    args: List<String>,
    commands: List<CliCommand> = COMMANDS,
    texts: CliTexts = CliTexts.ENGLISH,
): Command {
    val paths = mutableMapOf<String, Path>()
    val rest = mutableListOf<String>()
    var plain = false
    var index = 0
    while (index < args.size) {
        val arg = args[index++]
        if (arg == END) {
            rest += args.subList(index - 1, args.size)
            break
        }
        if (arg == PLAIN) {
            plain = true
            continue
        }
        val option = arg.substringBefore('=')
        if (option == CONFIG || option == DATA_DIR || option == CACHE_DIR) {
            val value = if ('=' in arg) arg.substringAfter('=') else args.getOrNull(index++)
            if (value.isNullOrEmpty() || value.startsWith("--")) {
                return Command.Invalid(texts("usage.needs_value", "option" to option))
            }
            if (option in paths) return Command.Invalid(texts("usage.given_twice", "option" to option))
            paths[option] = try {
                Path.of(value)
            } catch (e: InvalidPathException) {
                return Command.Invalid(texts("usage.invalid_path", "option" to option, "reason" to e.reason))
            }
        } else {
            rest += arg
        }
    }
    val options = Options(paths[CONFIG], paths[DATA_DIR], paths[CACHE_DIR], plain)
    if (rest.isEmpty() || rest.first().startsWith("-")) {
        rest.firstOrNull { it != HELP && it != VERSION }?.let {
            return Command.Invalid(texts("usage.unknown_option", "option" to it))
        }
        return when {
            HELP in rest -> Command.Help()
            VERSION in rest -> Command.Version
            else -> Command.Run(options)
        }
    }
    val words = rest.takeWhile { !it.startsWith("-") }
    val command = commands.filter { words.take(it.words.size) == it.words }.maxByOrNull { it.words.size }
        ?: return unknown(words, commands, texts)
    val action = try {
        command.read(given(command, rest.drop(command.words.size)) ?: return Command.Help(command))
    } catch (e: UsageException) {
        return Command.Invalid(texts(e.text), command)
    }
    return when (action) {
        CliAction.Foreground -> Command.Run(options)
        is CliAction.Ask -> Command.Control(options, action.request)
        is CliAction.Act -> Command.Act(options, command, action)
    }
}

/** What [arguments] give [command], null when they ask for its help. */
private fun given(command: CliCommand, arguments: List<String>): Given? {
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
                if (value == null || value.startsWith("--")) throw UsageException("usage.needs_value", "option" to name)
                options.getOrPut(name, ::mutableListOf) += value
            }

            arg.startsWith("-") && arg != "-" && arg.getOrNull(1)?.isDigit() != true ->
                throw UsageException("usage.unknown_option", "option" to arg)

            else -> operands += arg
        }
    }
    return Given(operands, flags, options)
}

private fun unknown(words: List<String>, commands: List<CliCommand>, texts: CliTexts): Command {
    val group = commands.filter { it.words.size > 1 && it.words.first() == words.first() }
    if (group.isEmpty()) return Command.Invalid(texts("usage.unknown_command", "command" to words.first()))
    val subcommands = group.map { it.words[1] }.distinct().joinToString(", ")
    val given = words.getOrNull(1) ?: return Command.Invalid(
        texts("usage.needs_subcommand", "command" to words.first(), "subcommands" to subcommands),
    )
    return Command.Invalid(
        texts(
            "usage.unknown_subcommand",
            "command" to words.first(),
            "given" to given,
            "subcommands" to subcommands,
        ),
    )
}

/** The usage of every command, with the default locations [defaults]. */
internal fun usage(
    defaults: Locations,
    commands: List<CliCommand> = COMMANDS,
    texts: CliTexts = CliTexts.ENGLISH,
): String = buildString {
    appendLine(
        texts(
            "usage.line",
            "options" to "[$CONFIG <file>] [$DATA_DIR <dir>] [$CACHE_DIR <dir>] [$PLAIN] [$VERSION] [$HELP]",
        ),
    )
    appendLine(texts("usage.line_command", "help" to HELP))
    appendLine()
    appendLine(texts("usage.about"))
    for ((area, members) in commands.groupBy { it.area }) {
        appendLine()
        appendLine(texts("usage.area", "area" to Text(area)))
        for (command in members) {
            val line = "${command.name} ${command.synopsis}".trimEnd()
            val summary = texts(command.textKey)
            if (line.length < COLUMN) {
                appendLine("  ${line.padEnd(COLUMN)}$summary")
            } else {
                appendLine("  $line")
                appendLine("  ${"".padEnd(COLUMN)}$summary")
            }
        }
    }
    appendLine()
    appendLine(texts("usage.options"))
    val options = listOf(
        "$CONFIG <file>" to texts("usage.option.config", "default" to defaults.configFile),
        "$DATA_DIR <dir>" to texts("usage.option.data_dir", "default" to defaults.dataDir),
        "$CACHE_DIR <dir>" to texts("usage.option.cache_dir", "default" to defaults.cacheDir),
        PLAIN to texts("usage.option.plain"),
        VERSION to texts("usage.option.version"),
        HELP to texts("usage.option.help"),
    )
    for ((option, about) in options) appendLine("  ${option.padEnd(OPTION_COLUMN)}$about")
    appendLine()
    append(texts("usage.defaults"))
}

/** Where the descriptions start in the usage's list of options. */
private const val OPTION_COLUMN = 20

/** The usage of [command]. */
internal fun usage(command: CliCommand, texts: CliTexts = CliTexts.ENGLISH): String = buildString {
    appendLine(texts("usage.of", "usage" to command.usage))
    appendLine()
    append(texts("usage.summary", "summary" to texts(command.textKey).replaceFirstChar(Char::uppercaseChar)))
    val help = "${command.textKey}.help"
    if (texts.has(help)) {
        append("\n\n").append(texts(help, "changes" to Text("help.changes"), "json" to Text("help.json")))
    }
}
