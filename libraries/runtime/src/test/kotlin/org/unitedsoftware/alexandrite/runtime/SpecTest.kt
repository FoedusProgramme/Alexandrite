package org.unitedsoftware.alexandrite.runtime

import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.runtime.plugin.BuiltInLayer
import org.unitedsoftware.alexandrite.runtime.plugin.BuiltInPlugin
import org.unitedsoftware.alexandrite.runtime.plugin.DisabledPlugin
import org.unitedsoftware.alexandrite.runtime.plugin.PluginSet
import org.unitedsoftware.alexandrite.sdk.config.ConfigSource
import org.unitedsoftware.alexandrite.sdk.di.container.DiProblemKind
import org.unitedsoftware.alexandrite.sdk.problem.Problem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SpecTest {
    private val dataDir = Path.of("data")

    private val error = RuntimeStartException("failed", StartStage.START, emptyList(), null, null)

    private val problem = Problem(RuntimeProblemKind.SHUTDOWN, "drain failed", "weather")

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
    fun `a runtime config keeps its cache in its data directory unless told otherwise`() {
        assertEquals(dataDir.resolve("cache"), RuntimeConfig.builder(dataDir).build().cacheDir)
    }

    @Test
    fun `a runtime config rejects a negative grace, a start timeout that is not positive and a blank name`() {
        val builder = RuntimeConfig.builder(dataDir)
        val rejected = listOf(
            { builder.shutdownGrace((-1).seconds) },
            { builder.startTimeout(Duration.ZERO) },
            { builder.name(" ") },
        )

        rejected.forEach { assertFailsWith<IllegalArgumentException> { it() } }
    }

    @Test
    fun `a runtime config rejects a cache directory that is the data directory, holds it or lies in plugin data`() {
        val rejected = listOf(
            dataDir,
            Path.of("data/../data"),
            dataDir.toAbsolutePath(),
            Path.of(""),
            Path.of("data/plugins/weather"),
        )

        for (cache in rejected) {
            assertFailsWith<IllegalArgumentException>("$cache") {
                RuntimeConfig.builder(dataDir).cacheDir(cache).build()
            }
        }
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
            assertEquals(environment, spec.environment())
            assertEquals(JsonPrimitive("s3cret-value"), spec.pluginConfig.tree("plugins.notes")?.get("token"))
            assertNull(builder.pluginConfig(ConfigSource.EMPTY).build().configFile)
            assertFailsWith<ConfigFileException.Missing> { builder.configFile(file.resolveSibling("missing.json")) }
        } finally {
            Files.delete(file)
        }
    }

    // Values.

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

        assertEquals(ids.size, ids.toSet().size)
    }
}
