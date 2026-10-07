package org.foedusprogramme.alexandrite.app

import java.nio.file.InvalidPathException
import java.nio.file.Path

internal class Locations(val configFile: Path, val dataDir: Path, val cacheDir: Path)

private const val CONFIG_FILE = "alexandrite.json"
private const val NAME = "alexandrite"
private const val TITLE = "Alexandrite"
private const val CACHE = "cache"

private enum class Platform { MAC, WINDOWS, UNIX }

/** Throws [IllegalArgumentException] for a variable that names no valid path. */
internal fun locations(options: Options, environment: Map<String, String>, osName: String, home: Path): Locations {
    fun own(name: String): Path? = environment[name]?.takeIf { it.isNotEmpty() }?.let { value ->
        try {
            Path.of(value)
        } catch (e: InvalidPathException) {
            throw IllegalArgumentException("$name names no valid path: ${e.reason}.", e)
        }
    }
    fun xdg(name: String): Path? = own(name)?.takeIf { it.isAbsolute }?.resolve(NAME)

    val platform = when {
        osName.startsWith("Mac", ignoreCase = true) || osName.startsWith("Darwin", ignoreCase = true) -> Platform.MAC
        osName.startsWith("Windows", ignoreCase = true) -> Platform.WINDOWS
        else -> Platform.UNIX
    }
    val roaming by lazy { own("APPDATA") ?: home.resolve("AppData").resolve("Roaming") }
    val local by lazy { own("LOCALAPPDATA") ?: home.resolve("AppData").resolve("Local") }
    val support = home.resolve("Library").resolve("Application Support").resolve(TITLE)
    val givenData = options.dataDir ?: own("ALEXANDRITE_DATA_DIR")
    return Locations(
        configFile = options.configFile ?: own("ALEXANDRITE_CONFIG")
            ?: xdg("XDG_CONFIG_HOME")?.resolve(CONFIG_FILE)
            ?: when (platform) {
                Platform.MAC -> support.resolve(CONFIG_FILE)
                Platform.WINDOWS -> roaming.resolve(TITLE).resolve(CONFIG_FILE)
                Platform.UNIX -> home.resolve(".config").resolve(NAME).resolve(CONFIG_FILE)
            },
        dataDir = givenData ?: xdg("XDG_DATA_HOME") ?: when (platform) {
            Platform.MAC -> support
            Platform.WINDOWS -> roaming.resolve(TITLE)
            Platform.UNIX -> home.resolve(".local").resolve("share").resolve(NAME)
        },
        cacheDir = options.cacheDir ?: own("ALEXANDRITE_CACHE_DIR") ?: givenData?.resolve(CACHE)
            ?: xdg("XDG_CACHE_HOME") ?: when (platform) {
            Platform.MAC -> home.resolve("Library").resolve("Caches").resolve(TITLE)
            Platform.WINDOWS -> local.resolve(TITLE).resolve("Cache")
            Platform.UNIX -> home.resolve(".cache").resolve(NAME)
        },
    )
}
