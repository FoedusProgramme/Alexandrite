package org.foedusprogramme.alexandrite.runtime.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.BotIndex
import org.foedusprogramme.alexandrite.runtime.Events
import org.foedusprogramme.alexandrite.runtime.Probe
import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.runtime.RuntimeSpec
import org.foedusprogramme.alexandrite.runtime.Service
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.runtime.TestIndex
import org.foedusprogramme.alexandrite.runtime.ZONE
import org.foedusprogramme.alexandrite.runtime.execute
import org.foedusprogramme.alexandrite.runtime.explicit
import org.foedusprogramme.alexandrite.runtime.hang
import org.foedusprogramme.alexandrite.runtime.probe
import org.foedusprogramme.alexandrite.runtime.service
import org.foedusprogramme.alexandrite.runtime.spec
import org.foedusprogramme.alexandrite.runtime.startFailure
import org.foedusprogramme.alexandrite.sdk.di.Key
import org.foedusprogramme.alexandrite.sdk.di.container.DiException
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind
import org.foedusprogramme.alexandrite.sdk.di.container.Scope
import org.foedusprogramme.alexandrite.sdk.di.container.binding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.hook.Delivery
import org.foedusprogramme.alexandrite.sdk.hook.Hook
import org.foedusprogramme.alexandrite.sdk.hook.Hooks
import org.foedusprogramme.alexandrite.sdk.hook.ObserverHook
import org.foedusprogramme.alexandrite.sdk.hook.ObserverPoint
import org.foedusprogramme.alexandrite.sdk.plugin.PluginFiles
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
import org.foedusprogramme.alexandrite.sdk.plugin.PluginScope
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.foedusprogramme.alexandrite.sdk.runtime.RuntimeControl
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class GraphTest {
    @TempDir
    lateinit var dataDir: Path

    private val events = Events()

    private val observed = ObserverPoint<String>("test.observed")

    private class Recording(
        private val events: Events,
        override val point: ObserverPoint<String>,
        private val block: suspend () -> Unit = {},
    ) : ObserverHook<String> {
        override val delivery: Delivery = Delivery.ASYNC
        override val timeout: Duration = Duration.INFINITE

        override suspend fun observe(payload: String) {
            block()
            events.record("observed $payload")
        }
    }

    private fun hooked(observe: suspend () -> Unit, shutdownGrace: Duration): RuntimeSpec {
        val hook = binding(key<Hook>(), "probe", "Recording", multi = true) { Recording(events, observed, observe) }
        val index = TestIndex("probe", bindings = listOf(probe("probe", "hooks" to key<Hooks>()), hook))
        return spec(explicit(index), dataDir, shutdownGrace = shutdownGrace)
    }

    private val AlexandriteRuntime.hooks: Hooks
        get() = services.get(key<Probe>()).values.getValue("hooks") as Hooks

    @Test
    fun `a graph problem fails the GRAPH stage with the container's problems`() {
        val plugins =
            explicit(TestIndex("weather", bindings = listOf(service("radar", "weather", events, listOf("dish")))))

        val error = spec(plugins, dataDir).startFailure()

        assertEquals(StartStage.GRAPH, error.stage)
        assertEquals(listOf(DiProblemKind.MISSING), error.problems.map { it.kind })
        assertIs<DiException>(error.cause)
        assertContains(
            error.message!!,
            "Cannot start runtime 'test': stage GRAPH failed (1 problem):\n- Missing binding",
        )
        assertEquals(emptyList(), events.all())
    }

    @Test
    fun `the runtime binds hooks, the clock, its control and each plugin's info, files and scope`() {
        val hook = binding(key<Hook>(), "probe", "Recording", multi = true) { Recording(events, observed) }
        val probe = probe(
            "probe",
            "hooks" to key<Hooks>(),
            "clock" to key<Clock>(),
            "info" to key<PluginInfo>("probe"),
            "files" to key<PluginFiles>("probe"),
            "other" to key<PluginFiles>("other"),
            "control" to key<RuntimeControl>(),
            "scope" to key<PluginScope>("probe"),
        )
        val index = TestIndex("probe", bindings = listOf(probe, hook))

        lateinit var hooks: Hooks

        spec(explicit(index, TestIndex("other")), dataDir).execute {
            val values = services.get(key<Probe>()).values
            hooks = values.getValue("hooks") as Hooks
            hooks.fire(observed, "early")
            val files = values.getValue("files") as PluginFiles

            assertEquals(ZONE, (values.getValue("clock") as Clock).zone)
            assertEquals(index.info, values.getValue("info"))
            assertFalse(Files.exists(dataDir.resolve("plugins")))
            assertFalse(Files.exists(dataDir.resolve("cache/plugins")))
            assertEquals(dataDir.resolve("plugins/probe"), files.dataDir)
            assertTrue(Files.isDirectory(dataDir.resolve("plugins/probe")))
            assertEquals(dataDir.resolve("cache/plugins/probe"), files.cacheDir)
            assertTrue(Files.isDirectory(dataDir.resolve("cache/plugins/probe")))
            assertEquals(dataDir.resolve("plugins/other"), (values.getValue("other") as PluginFiles).dataDir)
            assertTrue(values.getValue("control") is RuntimeControl)
            assertEquals("probe", (values.getValue("scope") as PluginScope).coroutineContext[CoroutineName]?.name)
        }

        runBlocking { hooks.fire(observed, "late") }
        assertEquals(listOf("observed early"), events.all())
    }

    @Test
    fun `a stop delivers the queued hook events before closing the container`() {
        hooked({ delay(50.milliseconds) }, shutdownGrace = 10.seconds).execute {
            listOf("a", "b").forEach { hooks.fire(observed, it) }
        }

        assertEquals(listOf("observed a", "observed b"), events.all())
    }

    @Test
    fun `a stop cancels the hook delivery at the shutdown grace and reports it`() {
        val cancelled = CompletableDeferred<Unit>()
        val hang: suspend () -> Unit = {
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        lateinit var returned: TimeMark

        val termination = hooked(hang, shutdownGrace = 100.milliseconds).execute {
            hooks.fire(observed, "a")
            returned = TimeSource.Monotonic.markNow()
        }

        val elapsed = returned.elapsedNow()

        runBlocking { withTimeout(5.seconds) { cancelled.await() } }
        assertTrue(elapsed >= 100.milliseconds, "the stop ended after $elapsed")
        assertEquals(emptyList(), events.all())
        assertEquals(
            listOf(
                Problem(
                    RuntimeProblemKind.DRAIN_TIMED_OUT,
                    "Draining Hooks (plugin alexandrite-runtime) was cancelled: the shutdown grace of 100ms ran out.",
                    "alexandrite-runtime",
                    null,
                ),
            ),
            termination.problems,
        )
    }

    // Channel instances.

    @Test
    fun `the channel instance graph of every enabled plugin is validated without creating it`() {
        val plugins = explicit(
            TestIndex("core", bindings = listOf(service("store", "core", events))),
            BotIndex(),
            TestIndex(
                "relay",
                bindings = listOf(service("bridge", "relay", events, listOf("socket"), Scope.CHANNEL_INSTANCE)),
            ),
        )

        val error = spec(plugins, dataDir).startFailure()

        assertEquals(StartStage.GRAPH, error.stage)
        assertEquals(
            listOf(
                Triple(DiProblemKind.MISSING, "alexandrite-channel-bot", key<Service>("token")),
                Triple(DiProblemKind.MISSING, "relay", key<Service>("socket")),
            ),
            error.problems.map { Triple(it.kind, it.plugin, it.key) },
        )
        assertContains(error.problems.first().message, "which bot (plugin alexandrite-channel-bot) needs")
        assertEquals(listOf("create store", "destroy store"), events.all())
    }

    @Test
    fun `two plugins may bind one key per channel instance`() {
        val driver = key<Service>("driver")
        val plugins = explicit(
            TestIndex("telegram", bindings = listOf(channel("bot", "telegram", driver))),
            TestIndex("discord", bindings = listOf(channel("guild", "discord", driver))),
        )

        spec(plugins, dataDir).execute()

        assertEquals(emptyList(), events.all())
    }

    private fun channel(name: String, plugin: String, key: Key<Service>) =
        service(name, plugin, events, scope = Scope.CHANNEL_INSTANCE, key = key)
}
