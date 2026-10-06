package org.foedusprogramme.alexandrite.testkit

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.runtime.Termination
import org.foedusprogramme.alexandrite.sdk.AlexandriteSdk
import org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.di.container.DiException
import org.foedusprogramme.alexandrite.sdk.di.container.binding
import org.foedusprogramme.alexandrite.sdk.di.container.instanceBinding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.plugin.PluginFiles
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
import org.foedusprogramme.alexandrite.sdk.runtime.StopKind
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
import kotlin.test.assertContains
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

    private fun PluginHarness.started(): PluginHarness = apply { runBlocking { start() } }

    @Test
    fun `a started harness resolves the plugin's components, contributions and runtime bindings`() {
        harness().started().use { harness ->
            val tool = harness.getAll<Tool>().single()

            assertEquals("hello", harness.get<Greeter>().greeting)
            assertEquals("carpe diem", harness.get<String>("motto"))
            assertEquals("echo", tool.definition.name)
            assertEquals(
                "chat-1",
                runBlocking {
                    tool.execute(JsonObject(emptyMap()), testToolContext("chat-1"))
                }.content,
            )
            assertEquals(ZoneOffset.UTC, harness.get<Clock>().zone)
            assertEquals(probe.info, harness.get<PluginInfo>("probe"))
        }
    }

    @Test
    fun `the plugin's config is placed below its config root`() {
        val text = harness { config("""{"greeting": "hi", "enabled": true}""") }.started().use { it.get<JsonObject>() }
        val tree = harness { config(JsonObject(mapOf("n" to JsonPrimitive(1)))) }.started().use { it.get<JsonObject>() }
        val none = harness().started().use { it.get<JsonObject>() }

        assertEquals(JsonObject(mapOf("greeting" to JsonPrimitive("hi"))), text)
        assertEquals(JsonObject(mapOf("n" to JsonPrimitive(1))), tree)
        assertEquals(JsonObject(emptyMap()), none)
        assertFailsWith<IllegalArgumentException> { PluginHarness.builder(probe).config("[1]") }
    }

    @Test
    fun `only the plugin under test and the plugins added to it run`() {
        val extra = Index("extra", listOf(instanceBinding(key<String>("extra"), "added", "extra", "extra")))

        harness { plugin(extra) }.started().use { harness ->
            assertEquals("added", harness.get<String>("extra"))
            assertEquals("extra", harness.get<PluginInfo>("extra").id)
            assertFailsWith<DiException> { harness.get<PluginInfo>("alexandrite-agent") }
        }
    }

    @Test
    fun `the harness names the plugin's own directories`() {
        harness().started().use { harness ->
            val files = harness.get<PluginFiles>("probe")

            assertEquals(directory.resolve("plugins/probe"), harness.dataDir)
            assertEquals(directory.resolve("cache/plugins/probe"), harness.cacheDir)
            assertEquals(harness.dataDir, files.dataDir)
            assertEquals(harness.cacheDir, files.cacheDir)
        }
    }

    @Test
    fun `a temporary data directory is deleted on close and a given one is kept`() {
        val temporary = PluginHarness.builder(probe).build().started()
        val root = temporary.dataDir.parent.parent
        temporary.get<PluginFiles>("probe").dataDir

        assertTrue(Files.isDirectory(temporary.dataDir))
        temporary.close()
        harness().started().use { it.get<PluginFiles>("probe").dataDir }

        assertFalse(Files.exists(root))
        assertTrue(Files.isDirectory(directory.resolve("plugins/probe")))
    }

    @Test
    fun `stop returns the termination and resolving needs a started runtime`() {
        val harness = harness { zone(ZoneId.of("Asia/Shanghai")).shutdownGrace(1.seconds) }

        val early = assertFailsWith<IllegalStateException> { harness.get<Greeter>() }
        harness.started()
        assertEquals(ZoneId.of("Asia/Shanghai"), harness.get<Clock>().zone)
        val termination = runBlocking { harness.stop() }
        val late = assertFailsWith<IllegalStateException> { harness.get<Greeter>() }
        harness.close()

        assertEquals("The harness of plugin 'probe' has not started: call start() first.", early.message)
        assertEquals(StopKind.SHUTDOWN, assertIs<Termination.Cause.Requested>(termination.cause).request.kind)
        assertContains(late.message!!, "it has stopped")
    }

    @Test
    fun `the test tool context names a conversation`() {
        assertEquals("test", testToolContext().conversationId)
        assertEquals("other", testToolContext("other").conversationId)
    }
}
