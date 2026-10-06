package org.foedusprogramme.alexandrite.testkit

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.runtime.Termination
import org.foedusprogramme.alexandrite.sdk.AlexandriteSdk
import org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec
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
import org.foedusprogramme.alexandrite.sdk.runtime.StopKind
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

        override suspend fun execute(arguments: JsonObject, context: ToolContext) = ToolResult(context.conversationId)
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
            assertEquals("chat-1", tool.execute(JsonObject(emptyMap()), testToolContext("chat-1")).content)
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
        val request = StopRequest(StopKind.RESTART, "again")

        val termination = harness { zone(ZoneId.of("Asia/Shanghai")).shutdownGrace(1.seconds) }.execute {
            assertEquals(ZoneId.of("Asia/Shanghai"), get<Clock>().zone)
            requestStop(request)
            val error = assertFailsWith<IllegalStateException> { get<Greeter>() }
            assertEquals("Runtime 'harness' has no services: a stop was requested.", error.message)
        }

        assertEquals(request, assertIs<Termination.Cause.Requested>(termination.cause).request)
        val returned = harness().execute {}
        assertEquals(StopKind.SHUTDOWN, assertIs<Termination.Cause.Requested>(returned.cause).request.kind)
    }

    @Test
    fun `a failed start returns its termination without running the block`() {
        val missing = Dependency(key<String>("missing"), DependencyKind.INSTANCE, "missing")
        val greeter = binding(key<Greeter>(), "broken", "Greeter", dependencies = listOf(missing)) { Greeter("never") }
        var ran = false

        val termination =
            PluginHarness.builder(Index("broken", listOf(greeter))).dataRoot(directory).build().execute { ran = true }

        assertEquals(StartStage.GRAPH, assertIs<Termination.Cause.StartFailed>(termination.cause).error.stage)
        assertFalse(ran)
    }

    @Test
    fun `the test tool context names a conversation`() {
        assertEquals("test", testToolContext().conversationId)
        assertEquals("other", testToolContext("other").conversationId)
    }
}
