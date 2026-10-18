package org.unitedsoftware.alexandrite.runtime.lifecycle

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
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.runtime.AlexandriteRuntime
import org.unitedsoftware.alexandrite.runtime.BotIndex
import org.unitedsoftware.alexandrite.runtime.ConfigFile
import org.unitedsoftware.alexandrite.runtime.Events
import org.unitedsoftware.alexandrite.runtime.Probe
import org.unitedsoftware.alexandrite.runtime.RuntimeConfig
import org.unitedsoftware.alexandrite.runtime.RuntimeProblemKind
import org.unitedsoftware.alexandrite.runtime.RuntimeSpec
import org.unitedsoftware.alexandrite.runtime.Service
import org.unitedsoftware.alexandrite.runtime.StartStage
import org.unitedsoftware.alexandrite.runtime.Termination
import org.unitedsoftware.alexandrite.runtime.TestIndex
import org.unitedsoftware.alexandrite.runtime.ZONE
import org.unitedsoftware.alexandrite.runtime.execute
import org.unitedsoftware.alexandrite.runtime.explicit
import org.unitedsoftware.alexandrite.runtime.hang
import org.unitedsoftware.alexandrite.runtime.hook.TestInterceptor
import org.unitedsoftware.alexandrite.runtime.logged
import org.unitedsoftware.alexandrite.runtime.probe
import org.unitedsoftware.alexandrite.runtime.service
import org.unitedsoftware.alexandrite.runtime.spec
import org.unitedsoftware.alexandrite.runtime.startFailure
import org.unitedsoftware.alexandrite.runtime.startVirtually
import org.unitedsoftware.alexandrite.runtime.store.MemoryRecordStore
import org.unitedsoftware.alexandrite.runtime.virtual
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatStates
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.agentState
import org.unitedsoftware.alexandrite.sdk.chat.state
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditException
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditor
import org.unitedsoftware.alexandrite.sdk.config.ConfigSource
import org.unitedsoftware.alexandrite.sdk.config.JsonConfigSource
import org.unitedsoftware.alexandrite.sdk.di.Key
import org.unitedsoftware.alexandrite.sdk.di.container.DiException
import org.unitedsoftware.alexandrite.sdk.di.container.DiProblemKind
import org.unitedsoftware.alexandrite.sdk.di.container.Scope
import org.unitedsoftware.alexandrite.sdk.di.container.binding
import org.unitedsoftware.alexandrite.sdk.di.container.instanceBinding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.hook.FailurePolicy
import org.unitedsoftware.alexandrite.sdk.hook.Hook
import org.unitedsoftware.alexandrite.sdk.hook.HookDecision
import org.unitedsoftware.alexandrite.sdk.hook.HookEffect
import org.unitedsoftware.alexandrite.sdk.hook.Hooks
import org.unitedsoftware.alexandrite.sdk.hook.Interception
import org.unitedsoftware.alexandrite.sdk.hook.InterceptorPoint
import org.unitedsoftware.alexandrite.sdk.hook.ObserverDelivery
import org.unitedsoftware.alexandrite.sdk.hook.ObserverHook
import org.unitedsoftware.alexandrite.sdk.hook.ObserverPoint
import org.unitedsoftware.alexandrite.sdk.plugin.PluginFiles
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo
import org.unitedsoftware.alexandrite.sdk.plugin.PluginScope
import org.unitedsoftware.alexandrite.sdk.problem.Problem
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import org.unitedsoftware.alexandrite.sdk.runtime.RuntimeControl
import org.unitedsoftware.alexandrite.sdk.store.ChatStateStore
import org.unitedsoftware.alexandrite.sdk.store.RecordStore
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
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
    fun `the runtime binds hooks, the clock, its control and each plugin's info, files, scope and config editor`() {
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
            "editor" to key<ConfigEditor>("probe"),
            "otherEditor" to key<ConfigEditor>("other"),
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
            assertTrue(values.getValue("editor") is ConfigEditor)
            assertNotSame(values.getValue("editor"), values.getValue("otherEditor"))
            assertEquals("probe", (values.getValue("scope") as PluginScope).coroutineContext[CoroutineName]?.name)
        }

        runBlocking { hooks.fire(observed, "late") }
        assertEquals(listOf("observed early"), events.all())
    }

    @Test
    fun `the runtime binds the host's paths, made absolute`() {
        val config = RuntimeConfig.builder(dataDir.resolve("root/."))
            .cacheDir(dataDir.resolve("cache"))
            .protect(dataDir.resolve("logs"))
            .protect(Path.of("install"))
            .protect(dataDir.resolve("logs/."))
            .build()
        val directory = dataDir.toRealPath()
        val file = Files.writeString(directory.resolve("alexandrite.json"), "{}")
        val probe = probe("probe", "paths" to key<HostPaths>(), "files" to key<PluginFiles>("probe"))
        val spec = RuntimeSpec.builder(config, explicit(TestIndex("probe", bindings = listOf(probe))))
            .configFile(directory.resolve("./alexandrite.json"))
            .build()

        lateinit var paths: HostPaths
        lateinit var files: PluginFiles

        spec.execute {
            val values = services.get(key<Probe>()).values
            paths = values.getValue("paths") as HostPaths
            files = values.getValue("files") as PluginFiles
        }

        assertEquals(dataDir.resolve("root"), paths.dataRoot)
        assertEquals(dataDir.resolve("root/plugins"), paths.pluginDataRoot)
        assertEquals(paths.pluginDataRoot.resolve("probe"), files.dataDir)
        assertEquals(dataDir.resolve("cache"), paths.cacheRoot)
        assertEquals(file, paths.configFile)
        assertEquals(paths.configFile, paths.configTarget)
        assertEquals(directory.resolve("alexandrite.json.bak"), paths.configBackup)
        assertEquals(listOf(dataDir.resolve("logs"), Path.of("install").toAbsolutePath()), paths.protected)
    }

    @Test
    fun `the runtime binds the file a linked config file names and the backup beside it`() {
        val target = Files.createDirectories(dataDir.resolve("dotfiles")).resolve("alexandrite.json")
        Files.writeString(target, "{}")
        val link = Files.createDirectories(dataDir.resolve("config")).resolve("alexandrite.json")
        Files.createSymbolicLink(link, target)
        val config = RuntimeConfig.builder(dataDir.resolve("root")).build()
        val probe = probe("probe", "paths" to key<HostPaths>())
        lateinit var paths: HostPaths

        val spec = RuntimeSpec.builder(config, explicit(TestIndex("probe", bindings = listOf(probe)))).configFile(link)
        spec.build().execute {
            paths = services.get(key<Probe>()).values.getValue("paths") as HostPaths
        }

        assertEquals(link, paths.configFile)
        assertEquals(target.toRealPath(), paths.configTarget)
        assertEquals(target.toRealPath().resolveSibling("alexandrite.json.bak"), paths.configBackup)
    }

    @Test
    fun `a runtime without a config file binds paths without one`() {
        val probe = probe("probe", "paths" to key<HostPaths>())
        lateinit var paths: HostPaths

        spec(explicit(TestIndex("probe", bindings = listOf(probe))), dataDir).execute {
            paths = services.get(key<Probe>()).values.getValue("paths") as HostPaths
        }

        assertEquals(listOf(dataDir, dataDir.resolve("cache")), listOf(paths.dataRoot, paths.cacheRoot))
        assertEquals(listOf(null, null, null), listOf(paths.configFile, paths.configTarget, paths.configBackup))
        assertEquals(emptyList(), paths.protected)
    }

    @Test
    fun `a host names its config file beside a source it derives from it, which edits do not write back to`() {
        val directory = dataDir.toRealPath()
        val file = Files.writeString(directory.resolve("alexandrite.json"), "{}")
        val config = RuntimeConfig.builder(directory.resolve("root")).build()
        val probe = probe("probe", "paths" to key<HostPaths>(), "editor" to key<ConfigEditor>("probe"))
        val plugins = explicit(TestIndex("probe", bindings = listOf(probe)))
        val derived = JsonConfigSource(checkNotNull(ConfigFile.read(file).tree("")))
        lateinit var named: HostPaths
        lateinit var refusal: ConfigEditException
        lateinit var unnamed: HostPaths

        RuntimeSpec.builder(config, plugins).pluginConfig(derived, file).build().execute {
            val values = services.get(key<Probe>()).values
            named = values.getValue("paths") as HostPaths
            val editor = values.getValue("editor") as ConfigEditor
            refusal = assertFailsWith<ConfigEditException> { editor.edit { set("note", JsonPrimitive("x")) } }
        }
        RuntimeSpec.builder(config, plugins).pluginConfig(ConfigFile.read(file)).build().execute {
            unnamed = services.get(key<Probe>()).values.getValue("paths") as HostPaths
        }

        assertEquals(file to file, named.configFile to named.configTarget)
        assertEquals(ConfigEditRefusal.NO_FILE, refusal.refusal)
        assertEquals("{}", Files.readString(file))
        assertNull(unnamed.configFile)
    }

    @Test
    fun `a config file that is gone when the paths are bound leaves every config path absolute`() {
        val given = Path.of("gone", "alexandrite.json")
        val config = RuntimeConfig.builder(dataDir.resolve("root")).build()
        val probe = probe("probe", "paths" to key<HostPaths>())
        lateinit var paths: HostPaths

        RuntimeSpec.builder(config, explicit(TestIndex("probe", bindings = listOf(probe))))
            .pluginConfig(ConfigSource.EMPTY, given)
            .build()
            .execute { paths = services.get(key<Probe>()).values.getValue("paths") as HostPaths }

        val file = given.toAbsolutePath()
        assertEquals(
            listOf(file, file, file.resolveSibling("alexandrite.json.bak")),
            listOf(paths.configFile, paths.configTarget, paths.configBackup),
        )
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
    fun `each plugin gets records of its own, kept in memory when no store is bound`() {
        val probe = probe("probe", "mine" to key<Records>("probe"), "theirs" to key<Records>("other"))

        spec(explicit(TestIndex("probe", bindings = listOf(probe)), TestIndex("other")), dataDir).execute {
            val values = services.get(key<Probe>()).values
            val mine = (values.getValue("mine") as Records).collection<Int>("counters")
            val theirs = (values.getValue("theirs") as Records).collection<Int>("counters")

            mine.put("a", 3, null)

            assertEquals(listOf("a" to 3), mine.list().map { it.key to it.value })
            assertEquals(null, theirs.get("a"))
        }
    }

    @Test
    fun `records are kept in the bound store`() {
        val store = MemoryRecordStore()
        val plugins = explicit(
            TestIndex("probe", bindings = listOf(probe("probe", "records" to key<Records>("probe")))),
            TestIndex("store", bindings = listOf(instanceBinding(key<RecordStore>(), store, "store", "Store"))),
        )

        spec(plugins, dataDir).execute {
            val records = services.get(key<Probe>()).values.getValue("records") as Records
            records.collection<LanguageTag>("languages").put("ada", LanguageTag("zh-CN"), null)
        }

        assertEquals("\"zh-CN\"", runBlocking { store.get("probe", "languages", "ada") }?.value)
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
