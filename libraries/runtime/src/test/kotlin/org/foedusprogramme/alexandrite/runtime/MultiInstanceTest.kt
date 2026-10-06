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

    private inner class Instance(val runtime: AlexandriteRuntime, val events: Events, val recorder: Recorder) {
        val values: Map<String, Any> get() = runtime.services.get(key<Probe>()).values

        fun fire(payload: String) = runBlocking { (values.getValue("hooks") as Hooks).fire(observed, payload) }
    }

    private fun instance(directory: String, zone: ZoneId): Instance {
        val events = Events()
        val recorder = Recorder()
        val hook = binding(key<Hook>(), "probe", "Recording", multi = true) { Recording(events, observed) }
        val probe = probe("probe", "hooks" to key<Hooks>(), "clock" to key<Clock>())
        val plugins = explicit(TestIndex("probe", bindings = listOf(probe, hook)))
        val runtime = runtime(plugins, dataDir.resolve(directory), listener = recorder, zone = zone)
        return Instance(runtime, events, recorder)
    }

    @Test
    fun `two runtimes run side by side with their own hooks, events and clock`() {
        val first = instance("first", ZoneId.of("Asia/Shanghai"))
        val second = instance("second", ZoneId.of("UTC"))

        first.runtime.started()
        second.runtime.started()
        first.fire("to first")
        second.fire("to second")
        first.runtime.stopped()

        assertEquals(RuntimeState.STOPPED, first.runtime.state.value)
        assertEquals(RuntimeState.READY, second.runtime.state.value)
        second.fire("still second")
        assertEquals(ZoneId.of("UTC"), (second.values.getValue("clock") as Clock).zone)
        second.runtime.close()
        assertEquals(listOf("to first"), first.events.all())
        assertEquals(listOf("to second", "still second"), second.events.all())
        val names = listOf("PluginsResolved", "Started", "Ready", "Stopping", "Stopped")
        assertEquals(names, first.recorder.names())
        assertEquals(names, second.recorder.names())
    }
}
