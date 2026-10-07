package org.foedusprogramme.alexandrite.app

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.RuntimeSpec
import org.foedusprogramme.alexandrite.runtime.Termination
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
import org.foedusprogramme.alexandrite.sdk.runtime.StopKind
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private typealias Execute = suspend (RuntimeSpec, suspend AlexandriteRuntime.() -> Unit) -> Termination

class MainTest {
    @TempDir
    lateinit var directory: Path

    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()

    private val home: Path get() = directory.resolve("home")
    private val dataDir: Path get() = directory.resolve("data")

    private fun config(json: String): Path = Files.writeString(directory.resolve("alexandrite.json"), json)

    private fun stopping(kind: StopKind = StopKind.SHUTDOWN, whileReady: AlexandriteRuntime.() -> Unit = {}): Execute =
        { spec, block ->
            AlexandriteRuntime.run(spec) {
                whileReady()
                coroutineScope {
                    launch(start = CoroutineStart.UNDISPATCHED) { block() }
                    stop(StopRequest(kind, "requested by the test"))
                }
            }
        }

    private fun host(
        vararg args: String,
        environment: Map<String, String> = emptyMap(),
        execute: Execute = stopping(),
    ): Int = run(args.toList(), environment, "Linux", home, PrintStream(out, true), PrintStream(err, true), execute)

    private fun hostWith(json: String, execute: Execute = stopping()): Int =
        host("--config", "${config(json)}", "--data-dir", "$dataDir", execute = execute)

    private fun stdout(): String = out.toString(Charsets.UTF_8)

    private fun stderr(): String = err.toString(Charsets.UTF_8)

    private fun logged(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger("org.foedusprogramme.alexandrite.app") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.map { "${it.level} ${it.formattedMessage}" }
    }

    @Test
    fun `--help prints the usage with this machine's defaults`() {
        val code = host("--help", environment = mapOf("ALEXANDRITE_DATA_DIR" to "/srv/alexandrite"))

        assertEquals(0, code)
        assertContains(stdout(), "Usage: alexandrite [--config <file>] [--data-dir <dir>] [--cache-dir <dir>]")
        assertContains(stdout(), "by default ${home.resolve(".config/alexandrite/alexandrite.json")}")
        assertContains(stdout(), "by default /srv/alexandrite")
        assertEquals("", stderr())
    }

    @Test
    fun `--version prints the version`() {
        assertEquals(0, host("--version"))

        assertTrue(Regex("""alexandrite \d+\.\d+\.\d+\S*\n""").matches(stdout()), stdout())
    }

    @Test
    fun `bad arguments print the reason and the usage to stderr and exit 64`() {
        assertEquals(64, host("--config"))

        assertTrue(stderr().startsWith("alexandrite: --config needs a value.\n\nUsage: alexandrite"), stderr())
        assertEquals("", stdout())
    }

    @Test
    fun `a missing config file exits 78 naming the file and the example`() {
        val missing = directory.resolve("absent.json")

        assertEquals(78, host("--config", "$missing", "--data-dir", "$dataDir"))

        assertContains(stderr(), "Config file $missing does not exist. Create it from the example at ")
        assertContains(stderr(), "config/alexandrite.example.json")
    }

    @Test
    fun `the config file is found by the location rules`() {
        val file = config("""{"bogus": {}}""")

        val code = host("--data-dir", "$dataDir", environment = mapOf("ALEXANDRITE_CONFIG" to "$file"))

        assertEquals(78, code)
        assertContains(stderr(), "Unknown config at 'bogus'")
    }

    @Test
    fun `a config file that is no JSON or refers to an unset variable exits 78`() {
        assertEquals(78, hostWith("""{"app": """))
        assertContains(stderr(), "is not valid JSON")

        assertEquals(78, hostWith($$"""{"plugins": {"notes": {"fileName": "${env:NOTES_FILE}"}}}"""))
        assertContains(stderr(), "the value at 'plugins.notes.fileName' refers to the environment variable NOTES_FILE")
    }

    @Test
    fun `invalid host settings exit 78`() {
        val cases = listOf(
            """{"app": {"zone": "Mars/Olympus"}}""" to "zone '***' is no time zone",
            """{"app": {"shutdownGraceSeconds": -1}}""" to "shutdownGraceSeconds may not be negative",
            """{"app": {"startTimeoutSeconds": 0}}""" to "startTimeoutSeconds must be positive",
            """{"app": {"plugins": ["Notes"]}}""" to "plugins holds '***', which is no plugin id",
            """{"app": {"plugin": []}}""" to "Invalid config at 'app': ",
            """{"app": []}""" to "Invalid config at 'app': must be an object",
        )

        for ((json, message) in cases) {
            err.reset()

            assertEquals(78, hostWith(json), json)
            assertContains(stderr(), message)
            assertFalse("Mars" in stderr() || "Notes" in stderr(), stderr())
        }
    }

    @Test
    fun `a named data directory gets a cache directory of its own`() {
        lateinit var cache: Path

        hostWith("{}") { spec, _ ->
            cache = spec.config.cacheDir
            AlexandriteRuntime.run(spec) {}
        }

        assertEquals(dataDir.resolve("cache"), cache)
    }

    @Test
    fun `a location variable that names no valid path exits 78 naming it`() {
        val invalid = mapOf("ALEXANDRITE_DATA_DIR" to "a\u0000b")

        assertEquals(78, host("--help", environment = invalid))
        assertEquals(78, host("--config", "${config("{}")}", environment = invalid))

        assertEquals(
            List(2) { "alexandrite: ALEXANDRITE_DATA_DIR names no valid path: Nul character not allowed." },
            stderr().lines().filter { it.isNotEmpty() },
        )
        assertEquals(0, host("--version", environment = invalid))
    }

    @Test
    fun `the host runs until signalled and exits by the stop a plugin requests`() {
        val code = run(
            listOf("--config", "${config("""{"app": {"plugins": ["stopper"]}, "plugins": {"stopper": {}}}""")}"),
            mapOf("ALEXANDRITE_DATA_DIR" to "$dataDir"),
            "Linux",
            home,
            PrintStream(out, true),
            PrintStream(err, true),
        )

        assertEquals(75, code)
        assertEquals("", stderr())
    }

    @Test
    fun `a plugin that is not on the class path exits 78`() {
        assertEquals(78, hostWith("""{"app": {"plugins": ["weather"]}}"""))

        assertContains(stderr(), "Cannot load the plugins of 'app.plugins': No plugin 'weather' on the class path")
    }

    @Test
    fun `a cache directory that holds the data directory exits 78`() {
        val code = host("--config", "${config("{}")}", "--data-dir", "$dataDir", "--cache-dir", "$directory")

        assertEquals(78, code)
        assertContains(stderr(), "The cache directory may not be the data directory or hold it")
    }

    @Test
    fun `a start that fails at CONFIG prints its problems and exits 78`() {
        assertEquals(78, hostWith("""{"plugins": {"notes": {"fileName": "../x.db"}}, "app": {"plugins": ["notes"]}}"""))

        assertContains(
            stderr(),
            "Cannot start runtime 'alexandrite': stage CONFIG failed (1 problem):\n- Invalid config",
        )
    }

    @Test
    fun `a start that fails at DATA_DIR exits 1`() {
        Files.writeString(dataDir, "")

        assertEquals(1, hostWith("{}"))
        assertContains(stderr(), "stage DATA_DIR failed")
    }

    @Test
    fun `an unexpected error exits 1`() {
        assertEquals(1, hostWith("{}") { _, _ -> error("boom") })

        assertContains(stderr(), "alexandrite: unexpected error: java.lang.IllegalStateException: boom")
    }

    @Test
    fun `a stop for a restart exits 75`() {
        assertEquals(75, hostWith("{}", stopping(StopKind.RESTART)))
    }

    @Test
    fun `a start that a stop cut short exits by the stop's kind and prints nothing`() {
        val cancelled = CoroutineScope(Job()).apply { cancel() }

        val code = hostWith("{}") { spec, _ ->
            AlexandriteRuntime.start(spec, cancelled)
            error("started")
        }

        assertEquals(0, code)
        assertEquals("", stderr())
    }

    @Test
    fun `the host runs the configured plugins until it is stopped and exits 0`() {
        var notes: PluginInfo? = null
        var settings: AppConfig? = null
        val json = """{"app": {"zone": "Asia/Shanghai", "plugins": ["notes"]}, "plugins": {"notes": {}}}"""

        val lines = logged {
            val code = hostWith(
                json,
                stopping {
                    notes = services.resolver().get(key<PluginInfo>("notes"))
                    settings = services.resolver().get(key<AppConfig>())
                },
            )

            assertEquals(0, code)
        }

        assertEquals("notes", notes?.id)
        assertEquals(ZoneId.of("Asia/Shanghai"), settings?.zoneId)
        assertEquals(listOf("notes"), settings?.plugins)
        assertTrue(Files.isRegularFile(dataDir.resolve("plugins/notes/notes.db")))
        assertContains(lines, "INFO Alexandrite $alexandriteVersion is ready")
        assertEquals("", stderr())
    }
}
