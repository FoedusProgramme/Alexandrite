package org.foedusprogramme.alexandrite.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.runtime.plugin.BuiltInLayer
import org.foedusprogramme.alexandrite.runtime.plugin.BuiltInPlugin
import org.foedusprogramme.alexandrite.runtime.plugin.DisabledPlugin
import org.foedusprogramme.alexandrite.runtime.plugin.PluginSet
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.config.ConfigSource
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SpecTest {
    private val dataDir = Path.of("data")

    private val error = RuntimeStartException("failed", StartStage.START, emptyList(), null, null)

    private val problem = Problem(RuntimeProblemKind.DRAIN_FAILED, "drain failed", "weather")

    private val values = listOf(
        RuntimeConfig.builder(dataDir).zone(ZONE).build(),
        loaded(HelloIndex()),
        BuiltInPlugin("a.AgentIndex", "alexandrite-agent", BuiltInLayer.AGENT, "agent"),
        DisabledPlugin("hello", DisabledPlugin.Reason.ENABLED_FALSE),
        RuntimeEvent.PluginsResolved(emptyList(), emptyList(), listOf("plugins.a")),
        RuntimeEvent.UnlistedIndexes(listOf("a.Index")),
        RuntimeEvent.StartFailed(error),
        RuntimeEvent.Stopping(RESTART),
        RuntimeEvent.Stopped(Termination(RESTART, emptyList())),
        Termination(RESTART, listOf(problem)),
    )

    // Builders.

    @Test
    fun `a runtime config needs only its data directory`() {
        val config = RuntimeConfig.builder(dataDir).build()

        assertEquals(dataDir, config.dataDir)
        assertEquals(dataDir.resolve("cache"), config.cacheDir)
        assertEquals(emptyList(), config.protected)
        assertEquals(ZoneId.systemDefault(), config.zone)
        assertEquals(LanguageTag("en"), config.language)
        assertEquals(15.seconds, config.shutdownGrace)
        assertEquals(30.seconds, config.startTimeout)
        assertEquals("alexandrite", config.name)
        assertSame(Dispatchers.Default, config.dispatcher)
    }

    @Test
    fun `a runtime config rejects a negative grace, a start timeout that is not positive and a blank name`() {
        val builder = RuntimeConfig.builder(dataDir)
        val rejected = listOf(
            { builder.shutdownGrace((-1).seconds) },
            { builder.startTimeout(Duration.ZERO) },
            { builder.name(" ") },
        )

        val messages = rejected.map { assertFailsWith<IllegalArgumentException> { it() }.message }

        assertEquals(
            listOf(
                "The shutdown grace may not be negative, was -1s.",
                "The start timeout must be positive, was 0s.",
                "The runtime name may not be blank.",
            ),
            messages,
        )
    }

    @Test
    fun `a runtime config rejects a cache directory that is the data directory, holds it or lies in plugin data`() {
        val rejected = listOf(dataDir, Path.of("data/../data"), dataDir.toAbsolutePath(), Path.of(""))

        val messages = rejected.map { cache ->
            assertFailsWith<IllegalArgumentException> { RuntimeConfig.builder(dataDir).cacheDir(cache).build() }.message
        }
        val inPlugins = assertFailsWith<IllegalArgumentException> {
            RuntimeConfig.builder(dataDir).cacheDir(Path.of("data/plugins/weather")).build()
        }

        assertEquals(
            "The cache directory may not be the data directory or hold it, was 'data' for the data directory 'data'.",
            messages.first(),
        )
        assertEquals(
            "The cache directory may not lie in the plugins' data, was 'data/plugins/weather' for the data " +
                "directory 'data'.",
            inPlugins.message,
        )
        RuntimeConfig.builder(dataDir).cacheDir(Path.of("data-cache")).build()
        RuntimeConfig.builder(dataDir).cacheDir(Path.of("data/cache")).build()
    }

    @Test
    fun `a runtime spec reads its config file once, with the environment it is given, and writes back to it`() {
        val file = Files.createTempFile("alexandrite-spec-", ".json")
        try {
            Files.writeString(file, "{\"plugins\": {\"notes\": {\"token\": \"\${env:TOKEN}\"}}}")
            val environment = mapOf("TOKEN" to "s3cret-value")
            val builder = RuntimeSpec.builder(RuntimeConfig.builder(dataDir).build(), PluginSet.of())

            val spec = builder.configFile(file, environment).build()
            Files.writeString(file, "{}")

            assertEquals(file, spec.configFile)
            assertEquals(environment, spec.environment)
            assertEquals(JsonPrimitive("s3cret-value"), spec.pluginConfig.tree("plugins.notes")?.get("token"))
            assertNull(builder.pluginConfig(ConfigSource.EMPTY).build().configFile)
            assertFailsWith<ConfigFileException.Missing> { builder.configFile(file.resolveSibling("missing.json")) }
        } finally {
            Files.delete(file)
        }
    }

    // Values.

    @Test
    fun `a loaded plugin prints its id instead of its whole info`() {
        assertEquals("LoadedPlugin(id=hello, layer=null, configRoot=plugins.hello)", loaded(HelloIndex()).toString())
    }

    @Test
    fun `values have no copy or component functions`() {
        for (value in values) {
            val type = value.javaClass
            assertTrue(type.methods.none { it.name == "copy" || it.name.startsWith("component") }, type.name)
        }
    }

    @Test
    fun `the problem kinds of the container and the runtime have ids unique across both`() {
        val ids = (DiProblemKind.entries + RuntimeProblemKind.entries).map { it.id }

        assertEquals("di.wrong_kind", DiProblemKind.WRONG_KIND.id)
        assertEquals("runtime.drain_failed", RuntimeProblemKind.DRAIN_FAILED.id)
        assertEquals(ids.size, ids.toSet().size)
    }
}
