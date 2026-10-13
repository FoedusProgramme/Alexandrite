package org.foedusprogramme.alexandrite.app

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LocationsTest {
    private val home = Path.of("/home/me")

    private fun at(os: String, environment: Map<String, String> = emptyMap(), options: Options = Options()) =
        locations(options, environment, os, home).let { listOf(it.configFile, it.dataDir, it.cacheDir) }

    private fun path(first: String, vararg more: String): Path = Path.of(first, *more)

    /** The directory [name] names for [os], in that host's own syntax. */
    private fun expectedXdg(os: String, name: String): Path = Path.of(xdgFor(os).getValue(name), "alexandrite")

    /** The directory the config file sits in for [os]. */
    private fun expectedXdgConfig(os: String): Path = expectedXdg(os, "XDG_CONFIG_HOME")

    private val systems = listOf("Mac OS X", "Linux", "Windows 11")

    private val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows")

    /**
     * The systems whose paths this host can render.
     *
     * A path is built with the host's own separator, so a POSIX path asked for on Windows, or a Windows path asked
     * for on Linux, does not come back as the test wrote it. The cases that only check precedence run against every
     * system; the ones that compare whole paths run against the ones this host shares a path syntax with.
     */
    private val hostSystems: List<String> = if (isWindows) listOf("Windows 11") else listOf("Mac OS X", "Linux")

    /**
     * The message a path that names no valid path is refused with.
     *
     * The wording differs between hosts, and Windows names the offending character itself: it reports a null as
     * `Illegal char <NUL>.`, with a real null inside the brackets, so the expected text is taken from the host
     * rather than written out here.
     */
    private fun pathRefusal(variable: String): String = if (isWindows) {
        "$variable names no valid path: Illegal char <${illegalCharacter()} >.".replace(" >", ">")
    } else {
        "$variable names no valid path: Nul character not allowed."
    }

    /** The character the host names as illegal in a path, as the host prints it. */
    private fun illegalCharacter(): String {
        val error = assertFailsWith<IllegalArgumentException> { Path.of("a\u0000b") }
        return error.message.orEmpty().substringAfter("Illegal char <").substringBeforeLast(">")
    }

    /**
     * Absolute directories to hand the XDG variables, which the code takes only when the host calls them absolute.
     *
     * A POSIX path such as `/xdg/data` is not absolute on Windows, so a case that checks the precedence of the XDG
     * variables has to name directories in the syntax of the host that resolves them. What the case is about is the
     * precedence, not the separator, so each host gets its own absolute directories.
     */
    private fun xdgFor(os: String): Map<String, String> = if (os.startsWith("Windows")) {
        mapOf(
            "XDG_CONFIG_HOME" to "C:\\xdg\\config",
            "XDG_DATA_HOME" to "C:\\xdg\\data",
            "XDG_CACHE_HOME" to "C:\\xdg\\cache",
        )
    } else {
        xdg
    }

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
        for (os in hostSystems) {
            assertEquals(
                listOf(
                    expectedXdg(os, "XDG_CONFIG_HOME").resolve("alexandrite.json"),
                    expectedXdg(os, "XDG_DATA_HOME"),
                    expectedXdg(os, "XDG_CACHE_HOME"),
                ),
                at(os, xdgFor(os)),
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
                at(os, xdgFor(os) + own),
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
            assertEquals(listOf(path("my.json"), path("data"), path("cache")), at(os, xdgFor(os) + own, options), os)
        }
    }

    @Test
    fun `each location falls back on its own`() {
        val os = hostSystems.first()
        val environment = xdgFor(os).filterKeys { it != "XDG_CONFIG_HOME" }

        assertEquals(
            listOf(Path.of("given.json"), expectedXdg(os, "XDG_DATA_HOME"), expectedXdg(os, "XDG_CACHE_HOME")),
            at(os, environment, Options(configFile = Path.of("given.json"))),
        )
    }

    @Test
    fun `a data directory that is named keeps its cache inside unless the cache is named too`() {
        val named = mapOf("ALEXANDRITE_DATA_DIR" to "/srv/data", "XDG_CACHE_HOME" to "/xdg/cache")

        for (os in systems) {
            assertEquals(path("/srv/data/cache"), at(os, named)[2], os)
            assertEquals(path("data/cache"), at(os, xdgFor(os), Options(dataDir = Path.of("data")))[2], os)
            assertEquals(path("/var/cache/alexandrite"), at(os, named + own)[2], os)
            assertEquals(path("cache"), at(os, named, Options(cacheDir = Path.of("cache")))[2], os)
        }
    }

    @Test
    fun `a variable that names no valid path is refused naming the variable`() {
        val error = assertFailsWith<IllegalArgumentException> { at("Linux", mapOf("XDG_DATA_HOME" to "/a\u0000b")) }

        assertEquals(pathRefusal("XDG_DATA_HOME"), error.message)
    }
}
