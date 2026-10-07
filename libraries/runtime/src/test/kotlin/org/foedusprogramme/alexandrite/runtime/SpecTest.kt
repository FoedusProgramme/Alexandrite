package org.foedusprogramme.alexandrite.runtime

import kotlinx.coroutines.Dispatchers
import org.foedusprogramme.alexandrite.runtime.plugin.BuiltInLayer
import org.foedusprogramme.alexandrite.runtime.plugin.BuiltInPlugin
import org.foedusprogramme.alexandrite.runtime.plugin.DisabledPlugin
import org.foedusprogramme.alexandrite.runtime.plugin.PluginSet
import org.foedusprogramme.alexandrite.sdk.config.ConfigSource
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import java.nio.file.Path
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SpecTest {
    private val dataDir = Path.of("data")

    private class Case(val make: () -> Any, val differing: Any, val printed: String)

    private val error = RuntimeStartException("failed", StartStage.START, emptyList(), null, null)

    private val problem = Problem(RuntimeProblemKind.DRAIN_FAILED, "drain failed", "weather", null)

    private val cases = listOf(
        Case(
            { RuntimeConfig.builder(dataDir).zone(ZONE).build() },
            RuntimeConfig.builder(dataDir).zone(ZONE).name("other").build(),
            "RuntimeConfig(dataDir=data, cacheDir=data${java.io.File.separator}cache, zone=Asia/Shanghai, " +
                "shutdownGrace=15s, startTimeout=30s, name=alexandrite, dispatcher=Dispatchers.Default)",
        ),
        Case(
            { loaded(HelloIndex()) },
            loaded(SpyIndex()),
            "LoadedPlugin(id=hello, layer=null, configRoot=plugins.hello)",
        ),
        Case(
            { BuiltInPlugin("a.AgentIndex", "alexandrite-agent", BuiltInLayer.AGENT, "agent") },
            BuiltInPlugin("a.AgentIndex", "alexandrite-agent", BuiltInLayer.TOOLS, "agent"),
            "BuiltInPlugin(indexClass=a.AgentIndex, id=alexandrite-agent, layer=AGENT, configRoot=agent)",
        ),
        Case(
            { DisabledPlugin("hello", DisabledPlugin.Reason.ENABLED_FALSE) },
            DisabledPlugin("hello", DisabledPlugin.Reason.NOT_CONFIGURED),
            "DisabledPlugin(id=hello, reason=ENABLED_FALSE)",
        ),
        Case(
            { RuntimeEvent.PluginsResolved(emptyList(), emptyList(), listOf("plugins.a")) },
            RuntimeEvent.PluginsResolved(emptyList(), emptyList(), emptyList()),
            "PluginsResolved(loaded=[], disabled=[], unknownPluginConfig=[plugins.a])",
        ),
        Case(
            { RuntimeEvent.UnlistedIndexes(listOf("a.Index")) },
            RuntimeEvent.UnlistedIndexes(emptyList()),
            "UnlistedIndexes(classes=[a.Index])",
        ),
        Case(
            { RuntimeEvent.StartFailed(error) },
            RuntimeEvent.StartFailed(RuntimeStartException("failed", StartStage.START, emptyList(), null, null)),
            "StartFailed(error=$error)",
        ),
        Case(
            { RuntimeEvent.Stopping(RESTART) },
            RuntimeEvent.Stopping(HOST_STOP),
            "Stopping(request=StopRequest(kind=RESTART, reason=update, plugin=null))",
        ),
        Case(
            { RuntimeEvent.Stopped(Termination(RESTART, emptyList())) },
            RuntimeEvent.Stopped(Termination(RESTART, listOf(problem))),
            "Stopped(termination=Termination(request=StopRequest(kind=RESTART, reason=update, plugin=null), " +
                "problems=[]))",
        ),
        Case(
            { Termination(RESTART, listOf(problem)) },
            Termination(HOST_STOP, listOf(problem)),
            "Termination(request=StopRequest(kind=RESTART, reason=update, plugin=null), problems=[$problem])",
        ),
    )

    // Builders.

    @Test
    fun `a runtime config needs only its data directory`() {
        val config = RuntimeConfig.builder(dataDir).build()

        assertEquals(dataDir, config.dataDir)
        assertEquals(dataDir.resolve("cache"), config.cacheDir)
        assertEquals(ZoneId.systemDefault(), config.zone)
        assertEquals(15.seconds, config.shutdownGrace)
        assertEquals(30.seconds, config.startTimeout)
        assertEquals("alexandrite", config.name)
        assertSame(Dispatchers.Default, config.dispatcher)
    }

    @Test
    fun `a runtime config keeps what its builder sets`() {
        val config = RuntimeConfig.builder(dataDir)
            .cacheDir(Path.of("cache"))
            .zone(ZONE)
            .shutdownGrace(Duration.ZERO)
            .startTimeout(5.milliseconds)
            .name("edge")
            .dispatcher(Dispatchers.IO)
            .build()

        assertEquals(
            listOf(Path.of("cache"), ZONE, Duration.ZERO, 5.milliseconds, "edge", Dispatchers.IO),
            with(config) {
                listOf(cacheDir, zone, shutdownGrace, startTimeout, name, dispatcher)
            },
        )
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
    fun `a runtime spec defaults to an empty plugin config and a listener that ignores events`() {
        val config = RuntimeConfig.builder(dataDir).build()
        val plugins = PluginSet.of()

        val spec = RuntimeSpec.builder(config, plugins).build()

        assertSame(config, spec.config)
        assertSame(plugins, spec.plugins)
        assertSame(ConfigSource.EMPTY, spec.pluginConfig)
        spec.listener.onEvent(RuntimeEvent.Started)
    }

    // Values.

    @Test
    fun `values are equal by their properties`() {
        for (case in cases) {
            assertEquals(case.make(), case.make())
            assertEquals(case.make().hashCode(), case.make().hashCode(), case.printed)
            assertNotEquals(case.make(), case.differing)
        }
    }

    @Test
    fun `values print their properties`() {
        for (case in cases) assertEquals(case.printed, case.make().toString())
    }

    @Test
    fun `values have no copy or component functions`() {
        for (case in cases) {
            val type = case.make().javaClass
            assertTrue(type.methods.none { it.name == "copy" || it.name.startsWith("component") }, type.name)
        }
    }
}
