package org.foedusprogramme.alexandrite.app

import java.nio.file.InvalidPathException
import java.nio.file.Path

/** The locations given on the command line. */
internal class Options(val configFile: Path? = null, val dataDir: Path? = null, val cacheDir: Path? = null)

/** What the command line asks for. */
internal sealed interface Command {
    class Run(val options: Options) : Command

    data object Help : Command

    data object Version : Command

    class Invalid(val message: String) : Command
}

private const val CONFIG = "--config"
private const val DATA_DIR = "--data-dir"
private const val CACHE_DIR = "--cache-dir"
private const val VERSION = "--version"
private const val HELP = "--help"

internal fun parseArguments(args: List<String>): Command {
    val paths = mutableMapOf<String, Path>()
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

            else -> return Command.Invalid("Unexpected argument $arg.")
        }
    }
    return when {
        help -> Command.Help
        version -> Command.Version
        else -> Command.Run(Options(paths[CONFIG], paths[DATA_DIR], paths[CACHE_DIR]))
    }
}

internal fun usage(defaults: Locations): String =
    """
    Usage: alexandrite [$CONFIG <file>] [$DATA_DIR <dir>] [$CACHE_DIR <dir>] [$VERSION] [$HELP]

    Runs Alexandrite until it receives SIGTERM or SIGINT.

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
