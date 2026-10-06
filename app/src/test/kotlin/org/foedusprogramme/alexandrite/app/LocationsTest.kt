package org.foedusprogramme.alexandrite.app

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class LocationsTest {
    private val home = Path.of("/home/me")

    private fun at(os: String, environment: Map<String, String> = emptyMap(), options: Options = Options()) =
        locations(options, environment, os, home).let { listOf(it.configFile, it.dataDir, it.cacheDir) }

    private fun path(first: String, vararg more: String): Path = Path.of(first, *more)

    private val systems = listOf("Mac OS X", "Linux", "Windows 11")

    private val xdg = mapOf(
        "XDG_CONFIG_HOME" to "/xdg/config",
        "XDG_DATA_HOME" to "/xdg/data",
        "XDG_CACHE_HOME" to "/xdg/cache",
    )

    private val own = mapOf(
        "ALEXANDRITE_CONFIG" to "/etc/alexandrite.json",
        "ALEXANDRITE_DATA_DIR" to "/var/lib/alexandrite",
        "ALEXANDRITE_CACHE_DIR" to "/var/cache/alexandrite",
    )

    // Platform defaults.

    @Test
    fun `macOS keeps config and data in Application Support and the cache in Caches`() {
        val support = home.resolve("Library").resolve("Application Support").resolve("Alexandrite")

        assertEquals(
            listOf(support.resolve("alexandrite.json"), support, home.resolve("Library/Caches/Alexandrite")),
            at("Mac OS X"),
        )
    }

    @Test
    fun `Linux and other Unix systems use the XDG default directories`() {
        for (os in listOf("Linux", "FreeBSD", "SunOS")) {
            assertEquals(
                listOf(
                    home.resolve(".config/alexandrite/alexandrite.json"),
                    home.resolve(".local/share/alexandrite"),
                    home.resolve(".cache/alexandrite"),
                ),
                at(os),
                os,
            )
        }
    }

    @Test
    fun `Windows uses APPDATA and LOCALAPPDATA`() {
        val environment = mapOf("APPDATA" to "C:\\Users\\me\\AppData\\Roaming", "LOCALAPPDATA" to "D:\\Local")
        val roaming = path("C:\\Users\\me\\AppData\\Roaming", "Alexandrite")

        assertEquals(
            listOf(roaming.resolve("alexandrite.json"), roaming, path("D:\\Local", "Alexandrite", "Cache")),
            at("Windows 11", environment),
        )
    }

    @Test
    fun `Windows without APPDATA or LOCALAPPDATA uses their usual places in the home directory`() {
        val roaming = home.resolve("AppData").resolve("Roaming").resolve("Alexandrite")

        assertEquals(
            listOf(
                roaming.resolve("alexandrite.json"),
                roaming,
                home.resolve("AppData").resolve("Local").resolve("Alexandrite").resolve("Cache"),
            ),
            at("Windows Server 2022", mapOf("APPDATA" to "")),
        )
    }

    // Precedence.

    @Test
    fun `XDG directories come before the platform default on every platform`() {
        for (os in systems) {
            assertEquals(
                listOf(
                    path("/xdg/config/alexandrite/alexandrite.json"),
                    path("/xdg/data/alexandrite"),
                    path("/xdg/cache/alexandrite"),
                ),
                at(os, xdg),
                os,
            )
        }
    }

    @Test
    fun `empty or relative XDG directories are ignored`() {
        val ignored = mapOf("XDG_CONFIG_HOME" to "", "XDG_DATA_HOME" to "relative/data", "XDG_CACHE_HOME" to "")

        assertEquals(at("Linux"), at("Linux", ignored))
    }

    @Test
    fun `ALEXANDRITE variables come before XDG directories`() {
        for (os in systems) {
            assertEquals(
                listOf(path("/etc/alexandrite.json"), path("/var/lib/alexandrite"), path("/var/cache/alexandrite")),
                at(os, xdg + own),
                os,
            )
        }
    }

    @Test
    fun `empty ALEXANDRITE variables are ignored`() {
        val empty = own.mapValues { "" }

        assertEquals(at("Linux", xdg), at("Linux", xdg + empty))
    }

    @Test
    fun `options come before the ALEXANDRITE variables`() {
        val options = Options(Path.of("my.json"), Path.of("data"), Path.of("cache"))

        for (os in systems) {
            assertEquals(listOf(path("my.json"), path("data"), path("cache")), at(os, xdg + own, options), os)
        }
    }

    @Test
    fun `each location falls back on its own`() {
        val mixed = mapOf("ALEXANDRITE_DATA_DIR" to "/srv/data", "XDG_CACHE_HOME" to "/xdg/cache")

        assertEquals(
            listOf(path("given.json"), path("/srv/data"), path("/xdg/cache/alexandrite")),
            at("Linux", mixed, Options(configFile = Path.of("given.json"))),
        )
    }
}
