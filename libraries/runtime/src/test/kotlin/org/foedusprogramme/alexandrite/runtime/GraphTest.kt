package org.foedusprogramme.alexandrite.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.foedusprogramme.alexandrite.sdk.di.DiException
import org.foedusprogramme.alexandrite.sdk.di.DiProblemKind
import org.foedusprogramme.alexandrite.sdk.di.Key
import org.foedusprogramme.alexandrite.sdk.di.Scope
import org.foedusprogramme.alexandrite.sdk.di.binding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.hook.Delivery
import org.foedusprogramme.alexandrite.sdk.hook.Hook
import org.foedusprogramme.alexandrite.sdk.hook.Hooks
import org.foedusprogramme.alexandrite.sdk.hook.ObserverHook
import org.foedusprogramme.alexandrite.sdk.hook.ObserverPoint
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
import org.foedusprogramme.alexandrite.sdk.runtime.PluginFiles
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
import kotlin.time.measureTime

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

    private fun hooked(block: suspend () -> Unit, shutdownGrace: Duration): Pair<AlexandriteRuntime, Hooks> {
        val hook = binding(key<Hook>(), "probe", "Recording", multi = true) { Recording(events, observed, block) }
        val index = TestIndex("probe", bindings = listOf(probe("probe", "hooks" to key<Hooks>()), hook))
        val runtime = runtime(explicit(index), dataDir, shutdownGrace = shutdownGrace).started()
        return runtime to runtime.services.get(key<Probe>()).values.getValue("hooks") as Hooks
    }

    @Test
    fun `a graph problem fails the GRAPH stage with the container's problems`() {
        val plugins =
            explicit(TestIndex("weather", bindings = listOf(service("radar", "weather", events, listOf("dish")))))

        val error = runtime(plugins, dataDir).use { it.startFailure() }

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
    fun `the runtime binds hooks, the clock and each plugin's info and files`() {
        val hook = binding(key<Hook>(), "probe", "Recording", multi = true) { Recording(events, observed) }
        val probe = probe(
            "probe",
            "hooks" to key<Hooks>(),
            "clock" to key<Clock>(),
            "info" to key<PluginInfo>("probe"),
            "files" to key<PluginFiles>("probe"),
            "other" to key<PluginFiles>("other"),
        )
        val index = TestIndex("probe", bindings = listOf(probe, hook))

        val hooks = runtime(explicit(index, TestIndex("other")), dataDir).started().use { runtime ->
            val values = runtime.services.get(key<Probe>()).values
            val hooks = values.getValue("hooks") as Hooks
            runBlocking { hooks.fire(observed, "early") }
            val files = values.getValue("files") as PluginFiles

            assertEquals(ZONE, (values.getValue("clock") as Clock).zone)
            assertEquals(index.info, values.getValue("info"))
            assertFalse(Files.exists(dataDir.resolve("plugins")))
            assertEquals(dataDir.resolve("plugins/probe"), files.dataDir)
            assertTrue(Files.isDirectory(dataDir.resolve("plugins/probe")))
            assertEquals(dataDir.resolve("plugins/other"), (values.getValue("other") as PluginFiles).dataDir)
            hooks
        }

        runBlocking { hooks.fire(observed, "late") }
        assertEquals(listOf("observed early"), events.all())
    }

    @Test
    fun `close delivers the queued hook events before closing the container`() {
        val (runtime, hooks) = hooked({ delay(50.milliseconds) }, shutdownGrace = 10.seconds)

        runtime.use {
            runBlocking { listOf("a", "b").forEach { hooks.fire(observed, it) } }

            runtime.close()

            assertEquals(listOf("observed a", "observed b"), events.all())
        }
    }

    @Test
    fun `close stops waiting for the hook events at the shutdown grace`() {
        val cancelled = CompletableDeferred<Unit>()
        val hang: suspend () -> Unit = {
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val (runtime, hooks) = hooked(hang, shutdownGrace = 100.milliseconds)

        val elapsed = runtime.use {
            runBlocking { hooks.fire(observed, "a") }
            measureTime { runtime.close() }
        }

        runBlocking { withTimeout(5.seconds) { cancelled.await() } }
        assertTrue(elapsed >= 100.milliseconds, "close returned after $elapsed")
        assertEquals(emptyList(), events.all())
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

        val error = runtime(plugins, dataDir).use { it.startFailure() }

        assertEquals(StartStage.GRAPH, error.stage)
        assertEquals(
            listOf(
                Triple(DiProblemKind.MISSING, "alexandrite-channel-bot", key<Service>("token")),
                Triple(DiProblemKind.MISSING, "relay", key<Service>("socket")),
            ),
            error.problems.map { Triple(it.kind, it.plugin, it.key) },
        )
        assertContains(error.problems.first().message, "which bot (plugin alexandrite-channel-bot) needs")
        assertEquals(listOf("create store", "close store"), events.all())
    }

    @Test
    fun `two plugins may bind one key per channel instance`() {
        val driver = key<Service>("driver")
        val plugins = explicit(
            TestIndex("telegram", bindings = listOf(channel("bot", "telegram", driver))),
            TestIndex("discord", bindings = listOf(channel("guild", "discord", driver))),
        )

        runtime(plugins, dataDir).started().close()

        assertEquals(emptyList(), events.all())
    }

    private fun channel(name: String, plugin: String, key: Key<Service>) =
        service(name, plugin, events, scope = Scope.CHANNEL_INSTANCE, key = key)
}
