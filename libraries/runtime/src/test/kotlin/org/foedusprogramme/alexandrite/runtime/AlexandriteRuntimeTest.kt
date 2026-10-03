package org.foedusprogramme.alexandrite.runtime

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.sdk.di.ModuleIndex
import org.foedusprogramme.alexandrite.sdk.di.Scope
import org.foedusprogramme.alexandrite.sdk.di.Startable
import org.foedusprogramme.alexandrite.sdk.di.binding
import org.foedusprogramme.alexandrite.sdk.di.key
import kotlin.test.Test
import kotlin.test.assertEquals

class AlexandriteRuntimeTest {
    @Test
    fun `discover loads module indexes from service descriptors`() {
        val runtime = AlexandriteRuntime.discover(javaClass.classLoader)

        assertEquals(listOf("runtime-test"), runtime.modules.map { it.module })
        runtime.close()
    }

    @Test
    fun `explicit indexes are sorted then build and start the root container`() {
        val events = mutableListOf<String>()
        val runtime = AlexandriteRuntime.create(
            listOf(
                index("z-module", events),
                index("a-module", events),
            ),
        )

        runBlocking { runtime.start() }
        runtime.close()

        assertEquals(
            listOf(
                "create a-module",
                "create z-module",
                "start a-module",
                "start z-module",
                "close z-module",
                "close a-module",
            ),
            events,
        )
    }

    @Test
    fun `a child creates the selected module's channel-instance-scoped bindings`() {
        val events = mutableListOf<String>()
        val runtime = AlexandriteRuntime.create(listOf(index("module", events, Scope.CHANNEL_INSTANCE)))

        assertEquals(emptyList(), events)
        runtime.child("channel-1", setOf("module")).close()

        assertEquals(listOf("create module", "close module"), events)
        runtime.close()
    }

    private fun index(module: String, events: MutableList<String>, scope: Scope = Scope.SINGLETON): ModuleIndex =
        object : ModuleIndex {
            override val module: String = module

            override fun bindings() = listOf(
                binding(
                    key<Service>(module),
                    module = module,
                    origin = "test $module",
                    scope = scope,
                ) {
                    events += "create $module"
                    Service(module, events)
                },
            )
        }

    private class Service(private val module: String, private val events: MutableList<String>) :
        Startable,
        AutoCloseable {
        override suspend fun start() {
            events += "start $module"
        }

        override fun close() {
            events += "close $module"
        }
    }
}
