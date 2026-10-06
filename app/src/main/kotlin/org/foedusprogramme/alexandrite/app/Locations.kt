package org.foedusprogramme.alexandrite.app

import java.nio.file.Path

internal class Locations(val configFile: Path, val dataDir: Path, val cacheDir: Path)

private const val CONFIG_FILE = "alexandrite.json"
private const val NAME = "alexandrite"
private const val TITLE = "Alexandrite"

private enum class Platform { MAC, WINDOWS, UNIX }

internal fun locations(options: Options, environment: Map<String, String>, osName: String, home: Path): Locations {
    fun variable(name: String): String? = environment[name]?.takeIf { it.isNotEmpty() }
    fun own(name: String): Path? = variable(name)?.let(Path::of)
    fun xdg(name: String): Path? = variable(name)?.let(Path::of)?.takeIf { it.isAbsolute }?.resolve(NAME)

    val platform = when {
        osName.startsWith("Mac", ignoreCase = true) || osName.startsWith("Darwin", ignoreCase = true) -> Platform.MAC
        osName.startsWith("Windows", ignoreCase = true) -> Platform.WINDOWS
        else -> Platform.UNIX
    }
    val roaming = own("APPDATA") ?: home.resolve("AppData").resolve("Roaming")
    val local = own("LOCALAPPDATA") ?: home.resolve("AppData").resolve("Local")
    val support = home.resolve("Library").resolve("Application Support").resolve(TITLE)
    return Locations(
        configFile = options.configFile ?: own("ALEXANDRITE_CONFIG")
            ?: xdg("XDG_CONFIG_HOME")?.resolve(CONFIG_FILE)
            ?: when (platform) {
                Platform.MAC -> support.resolve(CONFIG_FILE)
                Platform.WINDOWS -> roaming.resolve(TITLE).resolve(CONFIG_FILE)
                Platform.UNIX -> home.resolve(".config").resolve(NAME).resolve(CONFIG_FILE)
            },
        dataDir = options.dataDir ?: own("ALEXANDRITE_DATA_DIR") ?: xdg("XDG_DATA_HOME") ?: when (platform) {
            Platform.MAC -> support
            Platform.WINDOWS -> roaming.resolve(TITLE)
            Platform.UNIX -> home.resolve(".local").resolve("share").resolve(NAME)
        },
        cacheDir = options.cacheDir ?: own("ALEXANDRITE_CACHE_DIR") ?: xdg("XDG_CACHE_HOME") ?: when (platform) {
            Platform.MAC -> home.resolve("Library").resolve("Caches").resolve(TITLE)
            Platform.WINDOWS -> local.resolve(TITLE).resolve("Cache")
            Platform.UNIX -> home.resolve(".cache").resolve(NAME)
        },
    )
}
