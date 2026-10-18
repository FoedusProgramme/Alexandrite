package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.runtime.AlexandriteRuntime
import org.unitedsoftware.alexandrite.runtime.RuntimeSpec
import org.unitedsoftware.alexandrite.runtime.Termination
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import org.unitedsoftware.alexandrite.sdk.runtime.StopKind
import org.unitedsoftware.alexandrite.sdk.runtime.StopRequest
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
        install: Path? = null,
        textsInterval: Duration = 2.seconds,
    ): Int = run(
        args.toList(),
        environment,
        "Linux",
        home,
        PrintStream(out, true),
        PrintStream(err, true),
        execute,
        install,
        textsInterval,
    )

    private fun hostWith(json: String, execute: Execute = stopping()): Int =
        host("--config", "${config(json)}", "--data-dir", "$dataDir", execute = execute)

    private fun stdout(): String = out.toString(Charsets.UTF_8)

    private fun stderr(): String = err.toString(Charsets.UTF_8)

    @Test
    fun `--help prints the usage with this machine's defaults`() {
        val code = host("--help", environment = mapOf("ALEXANDRITE_DATA_DIR" to "/srv/alexandrite"))

        assertEquals(0, code)
        assertContains(stdout(), "Usage: alexandrite [--config <file>] [--data-dir <dir>] [--cache-dir <dir>]")
        assertContains(stdout(), "by default ${home.resolve(".config/alexandrite/alexandrite.json")}")
        assertContains(stdout(), "by default /srv/alexandrite")
        assertContains(stdout(), "\n  pair <code>                   pair the chat that was offered <code>\n")
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
    fun `a command without a running app says so and exits 69`() {
        val code = host("pairings", environment = mapOf("ALEXANDRITE_DATA_DIR" to "$dataDir"))

        assertEquals(69, code)
        assertEquals(
            "alexandrite: No Alexandrite runs with the data directory $dataDir: it holds no control.sock (a running " +
                "one logs why it serves none).\n",
            stderr(),
        )
        assertEquals("", stdout())
    }

    @Test
    fun `a command with bad arguments prints the reason and the usage and exits 64`() {
        assertEquals(64, host("unpair", "work"))

        assertTrue(stderr().startsWith("alexandrite: 'work' is no chat address"), stderr())
        assertContains(stderr(), "Usage: alexandrite")
    }

    @Test
    fun `the running host serves its control socket in the data directory until it stops`() {
        val socket = dataDir.resolve("control.sock")
        val answer = ByteArrayOutputStream()
        var permissions: String? = null
        var code: Int? = null
        val execute: Execute = { spec, block ->
            AlexandriteRuntime.run(spec) {
                coroutineScope {
                    launch { block() }
                    withContext(Dispatchers.IO) {
                        awaitControlSocket(socket)
                        permissions = PosixFilePermissions.toString(Files.getPosixFilePermissions(socket))
                        val printed = PrintStream(answer, true)
                        val args = listOf("pairings", "--data-dir", "$dataDir")
                        code = run(args, emptyMap(), "Linux", home, printed, printed)
                    }
                    stop(StopRequest.shutdown("requested by the test"))
                }
            }
        }

        val lines = logged { assertEquals(0, hostWith("{}", execute)) }

        assertEquals("rw-------", permissions)
        assertEquals(1, code)
        assertEquals("alexandrite: No channel takes pairing codes.\n", answer.toString(Charsets.UTF_8))
        assertContains(lines, "INFO Serving the control socket $socket")
        assertFalse(Files.exists(socket))
        assertEquals("", stderr())
    }

    @Test
    fun `a missing config file exits 78 naming the file and the example`() {
        val missing = directory.resolve("absent.json")

        assertEquals(78, host("--config", "$missing", "--data-dir", "$dataDir"))

        assertContains(stderr(), "Config file $missing does not exist. Create it from the example at ")
        assertContains(stderr(), "config/alexandrite.example.json")
    }

    @Test
    fun `a missing config file names the example of the distribution the host runs from`() {
        val install = Files.createDirectories(directory.resolve("dist"))
        val example = Files.createDirectories(install.resolve("config")).resolve("alexandrite.example.json")
        Files.writeString(example, "{}")

        host("--config", "${directory.resolve("absent.json")}", "--data-dir", "$dataDir", install = install)

        assertContains(stderr(), "Create it from the example at $example, or name another file")
    }

    @Test
    fun `the install directory is the one whose lib directory holds the jar`() {
        val jar = Files.createDirectories(directory.resolve("dist/lib")).resolve("alexandrite.jar")
        Files.writeString(jar, "")
        val elsewhere = Files.writeString(Files.createDirectories(directory.resolve("other")).resolve("a.jar"), "")

        assertEquals(directory.resolve("dist"), installDirectory(jar))
        assertEquals(null, installDirectory(directory.resolve("dist/lib")))
        assertEquals(null, installDirectory(elsewhere))
        assertEquals(null, installDirectory(null))
    }

    @Test
    fun `the host hands its config file, its roots and its install directory to the runtime`() {
        val file = config("{}")
        val install = directory.resolve("dist")
        var paths: HostPaths? = null

        val code = host(
            "--config",
            "$file",
            "--data-dir",
            "$dataDir",
            install = install,
            execute = stopping { paths = services.resolver().get(key<HostPaths>()) },
        )

        assertEquals(0, code)
        assertEquals(file.toAbsolutePath(), paths?.configFile)
        assertEquals(listOf(dataDir, dataDir.resolve("cache")), listOf(paths?.dataRoot, paths?.cacheRoot))
        assertEquals(listOf(install), paths?.protected)
    }

    @Test
    fun `the host hands its language to the runtime in canonical form, en unless set`() {
        val languages = mutableListOf<LanguageTag>()
        val execute: Execute = { spec, block ->
            languages += spec.config.language
            stopping()(spec, block)
        }

        assertEquals(0, hostWith("""{"app": {"language": "zh_hant_tw"}}""", execute))
        assertEquals(0, hostWith("{}", execute))

        assertEquals(listOf(LanguageTag("zh-Hant-TW"), LanguageTag("en")), languages)
    }

    @Test
    fun `the operator's texts take the place of the plugins' own and are read again when they change`() {
        val file = config("""{"app": {"language": "zh-CN", "plugins": ["greeter"]}, "plugins": {"greeter": {}}}""")
        val texts = Files.createDirectories(directory.resolve("i18n/greeter")).resolve("zh-CN.properties")
        Files.writeString(texts, "greeting=你好，{name}！\n")
        val seen = mutableListOf<String>()
        val execute: Execute = { spec, block ->
            AlexandriteRuntime.run(spec) {
                coroutineScope {
                    launch { block() }
                    val greeter = services.resolver().get(key<Texts>("greeter"))
                    fun greeting(language: LanguageTag?) = greeter.text("greeting", language, "name" to "Ada")
                    seen += greeting(null)
                    replace(texts, "greeting=嗨，{name}！\n")
                    withTimeout(10.seconds) { while (greeting(null) == seen.last()) delay(10.milliseconds) }
                    seen += listOf(greeting(null), greeting(LanguageTag("en")))
                    stop(StopRequest.shutdown("requested by the test"))
                }
            }
        }

        val lines = logged {
            val code =
                host("--config", "$file", "--data-dir", "$dataDir", execute = execute, textsInterval = 20.milliseconds)

            assertEquals(0, code)
        }

        assertEquals(listOf("你好，Ada！", "嗨，Ada！", "Hello, Ada!"), seen)
        assertContains(lines, "INFO Texts reloaded from ${directory.resolve("i18n")}: greeter/zh-CN.properties")
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
            """{"app": {"language": "Mars/Olympus"}}""" to "language '***' is no BCP 47 language tag",
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
