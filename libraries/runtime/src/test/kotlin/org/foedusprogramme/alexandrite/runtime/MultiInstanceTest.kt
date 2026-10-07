package org.foedusprogramme.alexandrite.runtime

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
        val runtime: AlexandriteRuntime = run {
            val hook = binding(key<Hook>(), "probe", "Recording", multi = true) { Recording(events, observed) }
            val probe = probe("probe", "hooks" to key<Hooks>(), "clock" to key<Clock>())
            val plugins = explicit(TestIndex("probe", bindings = listOf(probe, hook)))
            AlexandriteRuntime.launch(spec(plugins, dataDir.resolve(directory), listener = recorder, zone = zone))
        }

        suspend fun values(): Map<String, Any> {
            check(runtime.awaitReady())
            return runtime.services.get(key<Probe>()).values
        }

        suspend fun fire(payload: String) = (values().getValue("hooks") as Hooks).fire(observed, payload)
    }

    @Test
    fun `two launched runtimes run side by side with their own hooks, events and clock`() {
        val first = Instance("first", ZoneId.of("Asia/Shanghai"))
        val second = Instance("second", ZoneId.of("UTC"))

        runBlocking {
            first.fire("to first")
            second.fire("to second")
            first.runtime.requestStop()
            first.runtime.awaitTermination()

            assertEquals(RuntimeState.READY, second.runtime.state.value)
            second.fire("still second")
            assertEquals(ZoneId.of("UTC"), (second.values().getValue("clock") as Clock).zone)
            second.runtime.requestStop()
            second.runtime.awaitTermination()
        }

        assertEquals(listOf("to first"), first.events.all())
        assertEquals(listOf("to second", "still second"), second.events.all())
        val names = listOf("PluginsResolved", "Started", "Ready", "Stopping", "Stopped")
        assertEquals(names, first.recorder.names())
        assertEquals(names, second.recorder.names())
    }
}
