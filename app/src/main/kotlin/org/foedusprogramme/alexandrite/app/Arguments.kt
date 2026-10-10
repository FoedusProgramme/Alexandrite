package org.foedusprogramme.alexandrite.app

import org.foedusprogramme.alexandrite.app.control.ControlRequest
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** The locations given on the command line. */
internal class Options(val configFile: Path? = null, val dataDir: Path? = null, val cacheDir: Path? = null)

/** What the command line asks for. */
internal sealed interface Command {
    class Run(val options: Options) : Command

    /** A request to the app that runs with the same data directory. */
    class Control(val options: Options, val request: ControlRequest) : Command

    data object Help : Command

    data object Version : Command

    class Invalid(val message: String) : Command
}

private const val CONFIG = "--config"
private const val DATA_DIR = "--data-dir"
private const val CACHE_DIR = "--cache-dir"
private const val VERSION = "--version"
private const val HELP = "--help"

private const val PAIRINGS = "pairings"
private const val PAIR = "pair"
private const val DENY = "deny"
private const val UNPAIR = "unpair"
private const val EXAMPLE_CHAT = "telegram:work:123456789"

internal fun parseArguments(args: List<String>): Command {
    val paths = mutableMapOf<String, Path>()
    val operands = mutableListOf<String>()
    var help = false
    var version = false
    var index = 0
    while (index < args.size) {
        val arg = args[index++]
        val option = arg.substringBefore('=')
        when {
            arg == HELP -> help = true

            arg == VERSION -> version = true

            option == CONFIG || option == DATA_DIR || option == CACHE_DIR -> {
                val value = if ('=' in arg) arg.substringAfter('=') else args.getOrNull(index++)
                if (value.isNullOrEmpty() || value.startsWith("--")) return Command.Invalid("$option needs a value.")
                if (option in paths) return Command.Invalid("$option is given more than once.")
                paths[option] = try {
                    Path.of(value)
                } catch (e: InvalidPathException) {
                    return Command.Invalid("$option names no valid path: ${e.reason}.")
                }
            }

            arg.startsWith("-") -> return Command.Invalid("Unknown option $arg.")

            else -> operands += arg
        }
    }
    if (help) return Command.Help
    if (version) return Command.Version
    val options = Options(paths[CONFIG], paths[DATA_DIR], paths[CACHE_DIR])
    val command = operands.firstOrNull() ?: return Command.Run(options)
    val arguments = operands.drop(1)
    val code = arguments.joinToString(" ").takeIf { it.isNotEmpty() }
    val request = when (command) {
        PAIRINGS -> {
            if (arguments.isNotEmpty()) return Command.Invalid("$PAIRINGS takes no argument.")
            ControlRequest.ListPairings
        }

        PAIR -> ControlRequest.Approve(code ?: return Command.Invalid("$PAIR needs the code to approve."))

        DENY -> ControlRequest.Deny(code ?: return Command.Invalid("$DENY needs the code to deny."))

        UNPAIR -> {
            val chat = arguments.singleOrNull()
                ?: return Command.Invalid("$UNPAIR needs one chat address, such as $EXAMPLE_CHAT.")
            if (ChatAddress.parseOrNull(chat) == null) {
                return Command.Invalid("'$chat' is no chat address, such as $EXAMPLE_CHAT.")
            }
            ControlRequest.Unpair(chat)
        }

        else -> return Command.Invalid("Unknown command $command.")
    }
    return Command.Control(options, request)
}

internal fun usage(defaults: Locations): String =
    """
    Usage: alexandrite [$CONFIG <file>] [$DATA_DIR <dir>] [$CACHE_DIR <dir>] [$VERSION] [$HELP]
           alexandrite <command> [<argument>] [$DATA_DIR <dir>]

    Runs Alexandrite until it receives SIGTERM or SIGINT.

    Commands, answered by the Alexandrite that runs with the same data directory:
      $PAIRINGS            list the chats waiting to be paired, then the paired chats
      $PAIR <code>         pair the chat that was offered <code>
      $DENY <code>         drop the request of the chat that was offered <code>
      $UNPAIR <chat>       unpair a chat, such as $EXAMPLE_CHAT

    Options:
      $CONFIG <file>     the config file, by default ${defaults.configFile}
      $DATA_DIR <dir>    the data directory, by default ${defaults.dataDir}
      $CACHE_DIR <dir>   the cache directory, by default ${defaults.cacheDir}
      $VERSION           print the version and exit
      $HELP              print this help and exit

    Each default comes from ALEXANDRITE_CONFIG, ALEXANDRITE_DATA_DIR and ALEXANDRITE_CACHE_DIR when set,
    else from XDG_CONFIG_HOME, XDG_DATA_HOME and XDG_CACHE_HOME, else from the platform's own locations.
    The cache is the data directory's "cache" directory when only the data directory is named.
    """.trimIndent()
