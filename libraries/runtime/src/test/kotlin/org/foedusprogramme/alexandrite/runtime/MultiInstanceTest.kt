package org.foedusprogramme.alexandrite.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.sdk.di.container.binding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.hook.Hook
import org.foedusprogramme.alexandrite.sdk.hook.Hooks
import org.foedusprogramme.alexandrite.sdk.hook.ObserverHook
import org.foedusprogramme.alexandrite.sdk.hook.ObserverPoint
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MultiInstanceTest {
    @TempDir
    lateinit var dataDir: Path

    private val observed = ObserverPoint<String>("test.observed")

    private class Recording(private val events: Events, override val point: ObserverPoint<String>) :
        ObserverHook<String> {
        override suspend fun observe(payload: String) = events.record(payload)
    }

    private inner class Instance(directory: String, zone: ZoneId) {
        val events = Events()
        val recorder = Recorder()
        private val ready = CompletableDeferred<AlexandriteRuntime>()
        private val spec = run {
            val hook = binding(key<Hook>(), "probe", "Recording", multi = true) { Recording(events, observed) }
            val probe = probe("probe", "hooks" to key<Hooks>(), "clock" to key<Clock>())
            val plugins = explicit(TestIndex("probe", bindings = listOf(probe, hook)))
            spec(plugins, dataDir.resolve(directory), listener = recorder, zone = zone)
        }

        fun startIn(scope: CoroutineScope): Deferred<Termination> = scope.async(Dispatchers.Default) {
            AlexandriteRuntime.run(spec) {
                ready.complete(this)
                awaitCancellation()
            }
        }

        suspend fun values(): Map<String, Any> = ready.await().services.get(key<Probe>()).values

        suspend fun fire(payload: String) = (values().getValue("hooks") as Hooks).fire(observed, payload)

        suspend fun requestStop() = ready.await().requestStop()
    }

    @Test
    fun `two runtimes run side by side with their own hooks, events and clock`() {
        val first = Instance("first", ZoneId.of("Asia/Shanghai"))
        val second = Instance("second", ZoneId.of("UTC"))

        runBlocking {
            val firstRun = first.startIn(this)
            val secondRun = second.startIn(this)
            first.fire("to first")
            second.fire("to second")
            first.requestStop()
            firstRun.await()

            assertFalse(secondRun.isCompleted)
            second.fire("still second")
            assertEquals(ZoneId.of("UTC"), (second.values().getValue("clock") as Clock).zone)
            second.requestStop()
            secondRun.await()
        }

        assertEquals(listOf("to first"), first.events.all())
        assertEquals(listOf("to second", "still second"), second.events.all())
        val names = listOf("PluginsResolved", "Started", "Ready", "Stopping", "Stopped")
        assertEquals(names, first.recorder.names())
        assertEquals(names, second.recorder.names())
    }
}
