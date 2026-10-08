package org.foedusprogramme.alexandrite.runtime.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.BotIndex
import org.foedusprogramme.alexandrite.runtime.Events
import org.foedusprogramme.alexandrite.runtime.Probe
import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.runtime.RuntimeSpec
import org.foedusprogramme.alexandrite.runtime.Service
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.runtime.Termination
import org.foedusprogramme.alexandrite.runtime.TestIndex
import org.foedusprogramme.alexandrite.runtime.ZONE
import org.foedusprogramme.alexandrite.runtime.execute
import org.foedusprogramme.alexandrite.runtime.explicit
import org.foedusprogramme.alexandrite.runtime.hang
import org.foedusprogramme.alexandrite.runtime.hook.TestInterceptor
import org.foedusprogramme.alexandrite.runtime.logged
import org.foedusprogramme.alexandrite.runtime.probe
import org.foedusprogramme.alexandrite.runtime.service
import org.foedusprogramme.alexandrite.runtime.spec
import org.foedusprogramme.alexandrite.runtime.startFailure
import org.foedusprogramme.alexandrite.runtime.startVirtually
import org.foedusprogramme.alexandrite.runtime.virtual
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatStates
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.chat.agentState
import org.foedusprogramme.alexandrite.sdk.chat.state
import org.foedusprogramme.alexandrite.sdk.di.Key
import org.foedusprogramme.alexandrite.sdk.di.container.DiException
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind
import org.foedusprogramme.alexandrite.sdk.di.container.Scope
import org.foedusprogramme.alexandrite.sdk.di.container.binding
import org.foedusprogramme.alexandrite.sdk.di.container.instanceBinding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.hook.FailurePolicy
import org.foedusprogramme.alexandrite.sdk.hook.Hook
import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
import org.foedusprogramme.alexandrite.sdk.hook.HookEffect
import org.foedusprogramme.alexandrite.sdk.hook.Hooks
import org.foedusprogramme.alexandrite.sdk.hook.Interception
import org.foedusprogramme.alexandrite.sdk.hook.InterceptorPoint
import org.foedusprogramme.alexandrite.sdk.hook.ObserverDelivery
import org.foedusprogramme.alexandrite.sdk.hook.ObserverHook
import org.foedusprogramme.alexandrite.sdk.hook.ObserverPoint
import org.foedusprogramme.alexandrite.sdk.plugin.PluginFiles
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
import org.foedusprogramme.alexandrite.sdk.plugin.PluginScope
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.foedusprogramme.alexandrite.sdk.runtime.RuntimeControl
import org.foedusprogramme.alexandrite.sdk.store.ChatStateStore
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

@OptIn(ExperimentalCoroutinesApi::class)
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
        override val delivery: ObserverDelivery = ObserverDelivery.ASYNC
        override val timeout: Duration = Duration.INFINITE

        override suspend fun observe(payload: String) {
            block()
            events.record("observed $payload")
        }
    }

    private fun hooked(
        observe: suspend () -> Unit,
        shutdownGrace: Duration,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): RuntimeSpec {
        val hook = binding(key<Hook>(), "probe", "Recording", multi = true) { Recording(events, observed, observe) }
        val index = TestIndex("probe", bindings = listOf(probe("probe", "hooks" to key<Hooks>()), hook))
        return spec(explicit(index), dataDir, shutdownGrace = shutdownGrace, dispatcher = dispatcher)
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
            "control" to key<RuntimeControl>("probe"),
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
    fun `hooks of equal order run by plugin id, then in binding order`() {
        val point = InterceptorPoint<String>("test.order", setOf(HookEffect.REPLACE), FailurePolicy.FAIL_OPEN)
        fun hook(plugin: String, name: String, order: Int = 0) =
            binding(key<Hook>(), plugin, "sample.$name", multi = true) {
                TestInterceptor(point, order) { HookDecision.Replace("$it $name") }
            }
        val plugins = explicit(
            TestIndex("beta", bindings = listOf(hook("beta", "B1"), hook("beta", "B0", order = -1))),
            TestIndex(
                "alpha",
                bindings = listOf(hook("alpha", "A1"), hook("alpha", "A2"), probe("alpha", "hooks" to key<Hooks>())),
            ),
        )

        spec(plugins, dataDir).execute {
            val hooks = services.get(key<Probe>()).values.getValue("hooks") as Hooks

            assertEquals(Interception.Proceed("start B0 A1 A2 B1"), hooks.fire(point, "start"))
        }
    }

    @Test
    fun `each plugin gets chat states of its own, kept in memory when no store is bound`() {
        val probe = probe("probe", "mine" to key<ChatStates>("probe"), "theirs" to key<ChatStates>("other"))

        spec(explicit(TestIndex("probe", bindings = listOf(probe)), TestIndex("other")), dataDir).execute {
            val values = services.get(key<Probe>()).values
            val mine = (values.getValue("mine") as ChatStates).state("counter", 0)
            val theirs = (values.getValue("theirs") as ChatStates).state("counter", 0)

            mine.set(CHAT, 3)

            assertEquals(3, mine.get(CHAT))
            assertEquals(0, theirs.get(CHAT))
        }
    }

    @Test
    fun `chat states are kept in the bound store`() {
        val rows = mutableMapOf<String, String>()
        val store = object : ChatStateStore {
            override suspend fun read(plugin: String, name: String, agent: AgentId?, chat: ChatAddress): String? =
                rows["$plugin $name $agent $chat"]

            override suspend fun write(
                plugin: String,
                name: String,
                agent: AgentId?,
                chat: ChatAddress,
                json: String?,
            ) {
                if (json ==
                    null
                ) {
                    rows.remove("$plugin $name $agent $chat")
                } else {
                    rows["$plugin $name $agent $chat"] = json
                }
            }
        }
        val plugins = explicit(
            TestIndex("probe", bindings = listOf(probe("probe", "states" to key<ChatStates>("probe")))),
            TestIndex("store", bindings = listOf(instanceBinding(key<ChatStateStore>(), store, "store", "Store"))),
        )

        spec(plugins, dataDir).execute {
            val states = services.get(key<Probe>()).values.getValue("states") as ChatStates
            states.state("language", LanguageTag("en")).set(CHAT, LanguageTag("zh-CN"))
            states.agentState("language", LanguageTag("en")).set(AgentChatKey(AgentId.MAIN, CHAT), LanguageTag("fr"))
        }

        assertEquals(
            mapOf("probe language null $CHAT" to "\"zh-CN\"", "probe language main $CHAT" to "\"fr\""),
            rows,
        )
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
        lateinit var termination: Termination
        var stopped = 0L

        val lines = logged {
            runTest {
                val runtime = startVirtually(hooked(hang, shutdownGrace = 100.milliseconds, dispatcher = virtual()))
                runtime.hooks.fire(observed, "a")
                runtime.stop()
                termination = runtime.join()
                stopped = currentTime
                runCurrent()
            }
        }

        assertTrue(cancelled.isCompleted)
        assertEquals(100, stopped)
        assertEquals(emptyList(), events.all())
        assertContains(lines, "WARN test: hook ${Recording::class.java.name} failed at 'test.observed': Dropped")
        assertEquals(
            listOf(
                Problem(
                    RuntimeProblemKind.DRAIN_TIMED_OUT,
                    "Draining Hooks (plugin alexandrite-runtime) was cancelled: the shutdown grace of 100ms ran out.",
                    "alexandrite-runtime",
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
            listOf(DiProblemKind.MISSING to "alexandrite-channel-bot", DiProblemKind.MISSING to "relay"),
            error.problems.map { it.kind to it.plugin },
        )
        assertContains(error.problems.last().message, "nothing binds ${key<Service>("socket")}")
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

private val CHAT = ChatAddress(ChannelInstanceId(ChannelType("telegram"), "work"), "-100")
