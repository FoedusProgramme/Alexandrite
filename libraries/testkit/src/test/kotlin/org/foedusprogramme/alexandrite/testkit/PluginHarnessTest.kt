package org.foedusprogramme.alexandrite.testkit

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.runtime.RuntimeStartException
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.runtime.Termination
import org.foedusprogramme.alexandrite.runtime.decodeSection
import org.foedusprogramme.alexandrite.sdk.AlexandriteSdk
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.rebuild
import org.foedusprogramme.alexandrite.sdk.config.ConfigEditException
import org.foedusprogramme.alexandrite.sdk.config.ConfigEditRefusal
import org.foedusprogramme.alexandrite.sdk.config.ConfigEditor
import org.foedusprogramme.alexandrite.sdk.config.ConfigException
import org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec
import org.foedusprogramme.alexandrite.sdk.config.set
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.di.container.Dependency
import org.foedusprogramme.alexandrite.sdk.di.container.DependencyKind
import org.foedusprogramme.alexandrite.sdk.di.container.DiException
import org.foedusprogramme.alexandrite.sdk.di.container.binding
import org.foedusprogramme.alexandrite.sdk.di.container.instanceBinding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.plugin.PluginFiles
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
import org.foedusprogramme.alexandrite.sdk.runtime.HostPaths
import org.foedusprogramme.alexandrite.sdk.runtime.RuntimeControl
import org.foedusprogramme.alexandrite.sdk.runtime.StopKind
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import org.foedusprogramme.alexandrite.sdk.tool.FloorCheck
import org.foedusprogramme.alexandrite.sdk.tool.HardFloor
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PluginHarnessTest {
    @TempDir
    lateinit var directory: Path

    private class Index(
        id: String,
        private val bindings: List<Binding<*>> = emptyList(),
        private val sections: List<ConfigSectionSpec<*>> = emptyList(),
    ) : PluginIndex {
        override val info = PluginInfo(id, id, "1.0", "", AlexandriteSdk.API_VERSION, emptyList(), "test.Plugin")
        override val configRoot = PluginIds.thirdPartyRoot(id)

        override fun bindings() = bindings

        override fun configSections() = sections
    }

    private class Greeter(val greeting: String)

    private class Echo : Tool {
        override val definition =
            ToolDefinition("echo", "Echoes the conversation", JsonObject(emptyMap()), ToolRisk.READ_ONLY)

        override suspend fun execute(arguments: JsonObject, context: ToolContext) =
            ToolResult(context.turn.conversation.value)
    }

    private val probe = Index(
        "probe",
        listOf(
            binding(key<Greeter>(), "probe", "Greeter") { Greeter("hello") },
            instanceBinding(key<String>("motto"), "carpe diem", "probe", "motto"),
            binding(key<Tool>(), "probe", "Echo", multi = true) { Echo() },
        ),
        listOf(ConfigSectionSpec(key<JsonObject>(), "", JsonObject.serializer(), "Settings")),
    )

    private fun harness(configure: PluginHarness.Builder.() -> Unit = {}): PluginHarness =
        PluginHarness.builder(probe).dataRoot(directory).apply(configure).build()

    private fun PluginHarness.execute(block: suspend PluginHarness.Running.() -> Unit): Termination =
        runBlocking { run(block) }

    private fun settings(configure: PluginHarness.Builder.() -> Unit): JsonObject {
        lateinit var settings: JsonObject
        harness(configure).execute { settings = get() }
        return settings
    }

    @Test
    fun `a running harness resolves the plugin's components, contributions and runtime bindings`() {
        harness().execute {
            val tool = getAll<Tool>().single()

            assertEquals("hello", get<Greeter>().greeting)
            assertEquals("carpe diem", get<String>("motto"))
            assertEquals("echo", tool.definition.name)
            val turn = testToolContext().turn.rebuild { conversation(ConversationId("c1")) }
            assertEquals(ToolResult("c1"), tool.execute(JsonObject(emptyMap()), testToolContext(turn)))
            assertEquals(ZoneOffset.UTC, get<Clock>().zone)
            assertEquals(probe.info, get<PluginInfo>("probe"))
        }
    }

    @Test
    fun `the plugin's config is placed below its config root`() {
        val text = settings { config("""{"greeting": "hi", "enabled": true}""") }
        val tree = settings { config(JsonObject(mapOf("n" to JsonPrimitive(1)))) }
        val none = settings {}

        assertEquals(JsonObject(mapOf("greeting" to JsonPrimitive("hi"))), text)
        assertEquals(JsonObject(mapOf("n" to JsonPrimitive(1))), tree)
        assertEquals(JsonObject(emptyMap()), none)
        assertFailsWith<IllegalArgumentException> { PluginHarness.builder(probe).config("[1]") }
    }

    @Test
    fun `a plugin added to the harness gets its own config`() {
        val section = ConfigSectionSpec(key<JsonObject>("extra"), "", JsonObject.serializer(), "ExtraSettings")
        val extra = Index("extra", sections = listOf(section))
        lateinit var mine: JsonObject
        lateinit var theirs: JsonObject

        harness { plugin(extra).config("""{"own": 1}""").config(extra, """{"theirs": 2}""") }.execute {
            mine = get()
            theirs = get("extra")
        }

        assertEquals(JsonObject(mapOf("own" to JsonPrimitive(1))), mine)
        assertEquals(JsonObject(mapOf("theirs" to JsonPrimitive(2))), theirs)
    }

    @Test
    fun `only the plugin under test and the plugins added to it run`() {
        val extra = Index("extra", listOf(instanceBinding(key<String>("extra"), "added", "extra", "extra")))

        harness { plugin(extra) }.execute {
            assertEquals("added", get<String>("extra"))
            assertEquals("extra", get<PluginInfo>("extra").id)
            assertFailsWith<DiException> { get<PluginInfo>("alexandrite-agent") }
        }
    }

    @Test
    fun `the harness names the plugin's own directories`() {
        harness().execute {
            val files = get<PluginFiles>("probe")

            assertEquals(directory.resolve("plugins/probe"), dataDir)
            assertEquals(directory.resolve("cache/plugins/probe"), cacheDir)
            assertEquals(dataDir, files.dataDir)
            assertEquals(cacheDir, files.cacheDir)
        }
    }

    @Test
    fun `the host paths name the config file and the protected places the harness was given`() {
        val file = directory.resolve("config/alexandrite.json")
        val logs = directory.resolve("logs")

        harness { configFile(file).protect(logs).protect(directory.resolve("unit")) }.execute {
            val paths = get<HostPaths>()

            assertEquals(directory, paths.dataRoot)
            assertEquals(file, paths.configFile)
            assertEquals(listOf(logs, directory.resolve("unit")), paths.protected)
        }
        harness().execute {
            assertNull(get<HostPaths>().configFile)
            assertEquals(emptyList(), get<HostPaths>().protected)
        }
    }

    @Serializable
    data class Folders(val home: String? = null, val trusted: List<String> = emptyList())

    private val folders =
        Index("folders", sections = listOf(ConfigSectionSpec(key<Folders>(), "", Folders.serializer(), "Folders")))

    @Test
    fun `a plugin edits its own config in the harness's config file, which later runs start from`() {
        val file = directory.resolve("config/alexandrite.json")
        val harness =
            PluginHarness.builder(folders).dataRoot(directory).writeConfigFile(file).config("""{"home": "1"}""").build()
        val edited = Folders(trusted = listOf("/srv/repo"))

        harness.execute {
            val editor = get<ConfigEditor>("folders")

            editor.edit {
                set("trusted", JsonArray(listOf(JsonPrimitive("/srv/repo"))))
                remove("home")
            }
            val refused = assertFailsWith<ConfigEditException> { editor.set("bogus", JsonPrimitive(1)) }

            assertEquals(Folders("1"), get<Folders>())
            assertEquals(edited, readConfig().decodeSection("plugins.folders", Folders.serializer()))
            assertEquals(ConfigEditRefusal.INVALID_RESULT, refused.refusal)
        }
        harness.execute { assertEquals(edited, get<Folders>()) }

        assertTrue(Files.exists(file.resolveSibling("alexandrite.json.bak")))
    }

    @Test
    fun `a config file the test wrote is read as it is and keeps its env references`() {
        val home = "/home/test"
        val file = directory.resolve("alexandrite.json")
        val text = "{\"plugins\": {\"folders\": {\"home\": \"\${env:HOME}\"}}}\n"
        Files.writeString(file, text)
        val harness = PluginHarness.builder(folders)
            .dataRoot(directory)
            .writeConfigFile(file)
            .config("""{"home": "1"}""")
            .environment(mapOf("HOME" to home))
            .build()

        harness.execute {
            assertEquals(Folders(home), get<Folders>())

            get<ConfigEditor>("folders").set("trusted", JsonArray(listOf(JsonPrimitive("/srv"))))
        }

        assertEquals(text.replace("}}}", ", \"trusted\": [\"/srv\"]}}}"), Files.readString(file))
    }

    @Test
    fun `a config file the harness names gets the harness's config at each run, which edits reach until then`() {
        val file = directory.resolve("alexandrite.json")
        val named = harness { configFile(file).config("""{"greeting": "hi"}""") }

        named.execute {
            get<ConfigEditor>("probe").set("greeting", JsonPrimitive("hey"))

            assertEquals(JsonPrimitive("hey"), readConfig().tree("plugins.probe")?.get("greeting"))
        }
        named.execute { assertEquals(JsonObject(mapOf("greeting" to JsonPrimitive("hi"))), get<JsonObject>()) }
        harness().execute { assertFailsWith<IllegalStateException> { readConfig() } }
    }

    @Test
    fun `the harness's environment resolves the env references of its config as a config file's`() {
        val environment = mapOf("GREETING" to "hi from the environment")
        val config = """{"greeting": "${'$'}{env:GREETING}", "kept": "${'$'}${'$'}{GREETING}"}"""

        val resolved = settings { environment(environment).config(config) }
        val unset = assertFailsWith<ConfigException> { settings { environment(emptyMap()).config(config) } }
        val malformed = assertFailsWith<ConfigException> { settings { config("""{"greeting": "${'$'}{GREETING}"}""") } }

        assertEquals(JsonPrimitive("hi from the environment"), resolved["greeting"])
        assertEquals(JsonPrimitive("\${GREETING}"), resolved["kept"])
        assertEquals(
            "Invalid config at 'plugins.probe.greeting': the value from PluginHarness.Builder.config(...) refers to " +
                "the environment variable GREETING, which is not set.",
            unset.message,
        )
        assertEquals("plugins.probe.greeting", malformed.path)
        assertContains(malformed.message.orEmpty(), "PluginHarness.Builder.config(...) holds a malformed reference")
    }

    @Test
    fun `a config file no harness wrote is kept, and one a harness wrote gets each harness's config`() {
        val file = directory.resolve("alexandrite.json")
        val text = "{\"plugins\": {\"probe\": {\"greeting\": \"from the file\"}}}"
        Files.writeString(file, text)
        lateinit var kept: JsonObject

        harness { configFile(file).config("""{"greeting": "hi"}""") }.execute { kept = get() }
        val keptText = Files.readString(file)
        Files.delete(file)
        harness { configFile(file).config("""{"greeting": "first"}""") }.execute {}
        val second = settings { configFile(file).config("""{"greeting": "second"}""") }

        assertEquals(JsonObject(mapOf("greeting" to JsonPrimitive("from the file"))), kept)
        assertEquals(text, keptText)
        assertEquals(JsonObject(mapOf("greeting" to JsonPrimitive("second"))), second)
    }

    @Test
    fun `a kept config file keeps its layout where the harness splices in its channels' sections`() {
        val file = directory.resolve("alexandrite.json")
        val text = "{\n\t\"plugins\": {\n\t\t\"probe\": {\"greeting\": \"h\\u0069\"}\n\t}\n}\n"
        Files.writeString(file, text)

        harness { writeConfigFile(file).channel(admins = setOf("ada")) }.execute {
            assertTrue(channel().user("ada").isAdmin)
        }

        val section = "\t\t\"test-channel\": {\n\t\t\t\"instances\": {\n\t\t\t\t\"main\": {\n" +
            "\t\t\t\t\t\"admins\": [\"ada\"]\n\t\t\t\t}\n\t\t\t}\n\t\t}"
        assertEquals(text.replace("}\n\t}", "},\n$section\n\t}"), Files.readString(file))
    }

    @Test
    fun `a kept config file the test wrote gets the sections of the harness's recording channels`() {
        val file = directory.resolve("alexandrite.json")
        Files.writeString(file, "{\"plugins\": {\"probe\": {\"greeting\": \"hi\"}}}\n")
        val harness = harness { writeConfigFile(file).channel(admins = setOf("ada")) }

        harness.execute {
            assertTrue(channel().user("ada").isAdmin)
            assertEquals(JsonObject(mapOf("greeting" to JsonPrimitive("hi"))), get<JsonObject>())
        }
        val written = Files.readString(file)
        harness.execute { channel() }

        assertEquals(written, Files.readString(file))
    }

    @Test
    fun `the harness's own floor refuses the host's places and the places it protects`() {
        val logs = directory.resolve("logs")

        harness { hardFloor().protect(logs) }.execute {
            val floor = get<HardFloor>()

            assertEquals(FloorCheck.Denied("plugin data"), floor.checkRead(dataDir.resolve("notes.md")))
            assertEquals(FloorCheck.Denied("Alexandrite's cache"), floor.checkRead(cacheDir.resolve("notes.md")))
            assertEquals(FloorCheck.Denied("a place the host protects"), floor.checkRead(logs.resolve("run.log")))
            assertIs<FloorCheck.Allowed>(floor.checkRead(directory.resolveSibling("elsewhere.md")))
        }
    }

    @Test
    fun `a temporary data root is deleted when the run ends and a given one is kept`() {
        lateinit var root: Path

        PluginHarness.builder(probe).build().execute {
            get<PluginFiles>("probe").dataDir
            assertTrue(Files.isDirectory(dataDir))
            root = dataDir.parent.parent
        }
        harness().execute { get<PluginFiles>("probe").dataDir }

        assertFalse(Files.exists(root))
        assertTrue(Files.isDirectory(directory.resolve("plugins/probe")))
    }

    @Test
    fun `run returns the termination and resolving fails once a stop is requested`() {
        val request = StopRequest.restart("again")

        val termination = harness { zone(ZoneId.of("Asia/Shanghai")).shutdownGrace(1.seconds) }.execute {
            assertEquals(ZoneId.of("Asia/Shanghai"), get<Clock>().zone)
            stop(request)
            val error = assertFailsWith<IllegalStateException> { get<Greeter>() }
            assertEquals("Runtime 'harness' has no services: a stop was requested.", error.message)
        }

        assertEquals(request, termination.request)
        val returned = harness().execute {}
        assertEquals(StopKind.SHUTDOWN, returned.request.kind)
    }

    @Test
    fun `a temporary data root is deleted after a failed start too`() {
        val roots = mutableListOf<Path>()
        val builder = PluginHarness.builder(broken())
        builder.temporaryRoot = { Files.createTempDirectory(directory, "root-").also(roots::add) }

        assertFailsWith<RuntimeStartException> { builder.build().execute {} }

        assertEquals(1, roots.size)
        assertFalse(Files.exists(roots.single()))
    }

    @Test
    fun `deleting a temporary data root leaves the targets of links alone`() {
        assumeTrue("posix" in directory.fileSystem.supportedFileAttributeViews(), "no symbolic links")
        val outside = Files.createDirectories(directory.resolve("outside"))
        Files.writeString(outside.resolve("precious.txt"), "kept")
        lateinit var root: Path

        PluginHarness.builder(probe).build().execute {
            Files.createSymbolicLink(dataDir.resolve("link"), outside)
            root = dataDir.parent.parent
        }

        assertFalse(Files.exists(root))
        assertEquals("kept", Files.readString(outside.resolve("precious.txt")))
    }

    @Test
    fun `a temporary data root that cannot be deleted fails the run`() {
        assumeTrue("posix" in directory.fileSystem.supportedFileAttributeViews(), "no POSIX permissions")
        lateinit var locked: Path

        val error = assertFailsWith<IllegalStateException> {
            PluginHarness.builder(probe).build().execute {
                locked = Files.createDirectories(dataDir.resolve("locked"))
                Files.writeString(locked.resolve("file"), "")
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"))
            }
        }

        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"))
        val root = locked.parent.parent.parent
        assertContains(error.message!!, "Cannot delete the temporary data root $root")
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    // What a run checks.

    private class Stopper(private val control: RuntimeControl, private val failStop: Boolean) : Lifecycle {
        fun giveUp() = control.stop(StopRequest.failure("gave up"))

        override fun onStop() = check(!failStop) { "cannot stop" }
    }

    private fun stopper(failStop: Boolean = false) = Index(
        "stopper",
        listOf(
            binding(
                key<Stopper>(),
                "stopper",
                "Stopper",
                dependencies = listOf(Dependency(key<RuntimeControl>("stopper"), DependencyKind.INSTANCE, "control")),
            ) { r -> Stopper(r.get(key<RuntimeControl>("stopper")), failStop) },
        ),
    )

    @Test
    fun `a run whose block a plugin's stop cut short fails`() {
        val error = assertFailsWith<AssertionError> {
            PluginHarness.builder(stopper()).dataRoot(directory).build().execute {
                get<Stopper>().giveUp()
                awaitCancellation()
            }
        }

        assertEquals(
            "The run block was cut short by StopRequest(kind=FAILURE, reason=gave up, plugin=stopper).",
            error.message,
        )
    }

    @Test
    fun `a run whose stop met problems fails`() {
        val error = assertFailsWith<AssertionError> {
            PluginHarness.builder(stopper(failStop = true)).dataRoot(directory).build().execute {}
        }

        assertEquals(
            "Stopping the runtime met a problem:\n- Stopping Stopper (plugin stopper) failed: " +
                "java.lang.IllegalStateException: cannot stop",
            error.message,
        )
    }

    @Test
    fun `a test that inspects the termination gets it`() {
        val stopped = PluginHarness.builder(stopper()).dataRoot(directory).inspectTermination().build().execute {
            get<Stopper>().giveUp()
            awaitCancellation()
        }
        val failed =
            PluginHarness.builder(stopper(failStop = true)).dataRoot(directory).inspectTermination().build().execute {}

        assertEquals(StopRequest.failure("gave up").from("stopper"), stopped.request)
        assertEquals(listOf("cannot stop"), failed.problems.map { it.message.substringAfterLast(": ") })
    }

    private fun broken(): Index {
        val missing = Dependency(key<String>("missing"), DependencyKind.INSTANCE, "missing")
        val greeter = binding(key<Greeter>(), "broken", "Greeter", dependencies = listOf(missing)) { Greeter("never") }
        return Index("broken", listOf(greeter))
    }

    @Test
    fun `a failed start throws without running the block`() {
        val missing = Dependency(key<String>("missing"), DependencyKind.INSTANCE, "missing")
        val greeter = binding(key<Greeter>(), "broken", "Greeter", dependencies = listOf(missing)) { Greeter("never") }
        var ran = false

        val error = assertFailsWith<RuntimeStartException> {
            PluginHarness.builder(Index("broken", listOf(greeter))).dataRoot(directory).build().execute { ran = true }
        }

        assertEquals(StartStage.GRAPH, error.stage)
        assertNull(error.stopRequest)
        assertFalse(ran)
    }
}
