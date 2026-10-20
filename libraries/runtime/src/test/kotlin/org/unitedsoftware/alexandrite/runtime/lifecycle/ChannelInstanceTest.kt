package org.unitedsoftware.alexandrite.runtime.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.runtime.Events
import org.unitedsoftware.alexandrite.runtime.RuntimeProblemKind
import org.unitedsoftware.alexandrite.runtime.RuntimeSpec
import org.unitedsoftware.alexandrite.runtime.StartStage
import org.unitedsoftware.alexandrite.runtime.TestIndex
import org.unitedsoftware.alexandrite.runtime.execute
import org.unitedsoftware.alexandrite.runtime.explicit
import org.unitedsoftware.alexandrite.runtime.get
import org.unitedsoftware.alexandrite.runtime.logged
import org.unitedsoftware.alexandrite.runtime.spec
import org.unitedsoftware.alexandrite.runtime.startFailure
import org.unitedsoftware.alexandrite.runtime.startVirtually
import org.unitedsoftware.alexandrite.runtime.started
import org.unitedsoftware.alexandrite.runtime.terminated
import org.unitedsoftware.alexandrite.runtime.virtual
import org.unitedsoftware.alexandrite.sdk.channel.Channel
import org.unitedsoftware.alexandrite.sdk.channel.ChannelCapabilities
import org.unitedsoftware.alexandrite.sdk.channel.ChannelControl
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.ChannelInstance
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.InstanceState
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.channel.ReplyRequest
import org.unitedsoftware.alexandrite.sdk.channel.ReplySink
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.config.ConfigSectionSpec
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.di.container.Dependency
import org.unitedsoftware.alexandrite.sdk.di.container.DependencyKind
import org.unitedsoftware.alexandrite.sdk.di.container.DiProblemKind
import org.unitedsoftware.alexandrite.sdk.di.container.Scope
import org.unitedsoftware.alexandrite.sdk.di.container.binding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.plugin.PluginScope
import org.unitedsoftware.alexandrite.sdk.problem.Problem
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelInstanceTest {
    @TempDir
    lateinit var dataDir: Path

    private val events = Events()

    /** Whether the directory lists a channel, recorded at each step that [Bot] and [Agent] take. */
    private val listed = Events()

    private val work = ChannelInstanceId(ChannelType("chan"), "work")
    private val home = ChannelInstanceId(ChannelType("chan"), "home")

    private val twoInstances =
        """{"plugins": {"chan": {"instances": {"work": {"token": "w"}, "home": {"token": "h"}}}}}"""

    @Serializable
    class BotConfig(val token: String)

    /** What a test does at each lifecycle step of an instance. */
    private class Steps(
        val construct: (ChannelInstance) -> Unit = {},
        val start: suspend (ChannelInstance) -> Unit = {},
        val open: suspend (ChannelInstance) -> Unit = {},
        val close: suspend (ChannelInstance) -> Unit = {},
        val drain: suspend (ChannelInstance) -> Unit = {},
        val stop: (ChannelInstance) -> Unit = {},
    )

    /** A channel that records its steps and whether the directory lists it at each of them. */
    private inner class Bot(
        val instance: ChannelInstance,
        val config: BotConfig,
        private val directory: ChannelDirectory,
        private val steps: Steps,
    ) : TestChannel(),
        Lifecycle {
        private val name = instance.id.name

        private fun record(step: String) {
            events.record("$step $name")
            listed.record("$step $name ${directory.channel(instance.id) === this}")
        }

        override suspend fun onStart() {
            record("start")
            steps.start(instance)
        }

        override suspend fun onOpen() {
            record("open")
            instance.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    events.record("cancelled $name")
                }
            }
            steps.open(instance)
        }

        override suspend fun onClose() {
            record("close")
            steps.close(instance)
        }

        override suspend fun onDrain() {
            record("drain")
            steps.drain(instance)
        }

        override fun onStop() {
            record("stop")
            steps.stop(instance)
        }

        override fun onDestroy() = events.record("destroy $name")
    }

    /** A singleton of another plugin that records its steps and whether the directory lists `chan:work`. */
    private inner class Agent(
        private val directory: ChannelDirectory,
        private val scope: PluginScope,
        private val drain: suspend () -> Unit,
    ) : Lifecycle {
        private fun record(step: String) {
            events.record("$step agent")
            listed.record("$step agent ${directory.channel(work) != null}")
        }

        override suspend fun onStart() = record("start")

        override suspend fun onOpen() {
            record("open")
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    events.record("cancelled agent")
                }
            }
        }

        override suspend fun onClose() = record("close")

        override suspend fun onDrain() {
            record("drain")
            drain()
        }

        override fun onStop() = record("stop")

        override fun onDestroy() = events.record("destroy agent")
    }

    private val botSection = ConfigSectionSpec(key<BotConfig>(), "", BotConfig.serializer(), "BotConfig", CHANNEL)

    private fun bot(steps: Steps = Steps()): Binding<Channel> = binding(
        key<Channel>(),
        "chan",
        "Bot",
        CHANNEL,
        listOf(
            Dependency(key<ChannelInstance>(), DependencyKind.INSTANCE, "instance"),
            Dependency(key<BotConfig>(), DependencyKind.INSTANCE, "config"),
            Dependency(key<ChannelDirectory>(), DependencyKind.INSTANCE, "directory"),
        ),
        multi = true,
        name = "chan",
    ) { r ->
        val instance = r.get(key<ChannelInstance>())
        steps.construct(instance)
        events.record("create ${instance.id.name}")
        Bot(instance, r.get(key<BotConfig>()), r.get(key<ChannelDirectory>()), steps)
    }

    private fun chan(steps: Steps = Steps()) =
        TestIndex("chan", bindings = listOf(bot(steps)), sections = listOf(botSection))

    private fun agent(drain: suspend () -> Unit = {}) = TestIndex(
        "agent",
        bindings = listOf(
            binding(
                key<Agent>(),
                "agent",
                "Agent",
                dependencies = listOf(
                    Dependency(key<ChannelDirectory>(), DependencyKind.INSTANCE, "directory"),
                    Dependency(key<PluginScope>("agent"), DependencyKind.INSTANCE, "scope"),
                ),
            ) { r ->
                events.record("create agent")
                Agent(r.get(key<ChannelDirectory>()), r.get(key<PluginScope>("agent")), drain)
            },
        ),
    )

    private fun channels(
        steps: Steps = Steps(),
        config: String = twoInstances,
        shutdownGrace: Duration = 15.seconds,
    ): RuntimeSpec = spec(explicit(agent(), chan(steps)), dataDir, config, shutdownGrace = shutdownGrace)

    // Order.

    @Test
    fun `instances start and open after the root, close before it, drain after it and stop before it`() {
        channels().execute()

        assertEquals(
            listOf(
                "create agent", "create work", "create home",
                "start agent", "start work", "start home",
                "open agent", "open work", "open home",
                "close home", "close work", "close agent",
                "drain agent", "drain home", "drain work",
                "stop home", "cancelled home", "stop work", "cancelled work", "stop agent", "cancelled agent",
                "destroy home", "destroy work", "destroy agent",
            ),
            events.all(),
        )
    }

    @Test
    fun `each instance gets its own id, scope and config`() {
        channels().execute {
            val bots = services.resolver().get(key<ChannelDirectory>()).let { directory ->
                listOf(work, home).map { directory.channel(it) as Bot }
            }

            assertEquals(listOf(work, home), bots.map { it.instance.id })
            assertEquals(listOf("w", "h"), bots.map { it.config.token })
            assertTrue(bots[0].instance.scope !== bots[1].instance.scope)
            assertEquals(
                listOf("chan chan:work", "chan chan:home"),
                bots.map { it.instance.scope.coroutineContext[CoroutineName]?.name },
            )
        }
    }

    // Directory.

    @Test
    fun `the directory lists the configured instances and each channel from the end of its open until its stop`() {
        channels().execute {
            val directory = services.resolver().get(key<ChannelDirectory>())

            assertEquals(listOf(work, home), directory.instances.toList())
            assertSame(directory.channel(work), services.resolver().get(key<ChannelDirectory>()).channel(work))
            assertEquals(null, directory.channel(ChannelInstanceId(ChannelType("chan"), "away")))
        }

        assertEquals(
            listOf(
                "start agent false", "start work false", "start home false",
                "open agent false", "open work false", "open home false",
                "close home true", "close work true", "close agent true",
                "drain agent true", "drain home true", "drain work true",
                "stop home false", "stop work false", "stop agent false",
            ),
            listed.all(),
        )
    }

    @Test
    fun `a channel plugin without instances loads with a warning and the directory stays empty`() {
        val lines = logged {
            channels(config = """{"plugins": {"chan": {}}}""").execute {
                assertEquals(emptySet(), services.resolver().get(key<ChannelDirectory>()).instances)
            }
        }

        assertContains(
            lines,
            "WARN test: channel plugin chan has no channel instances: add them below 'plugins.chan.instances'",
        )
        assertEquals(listOf("create agent", "start agent", "open agent"), events.all().take(3))
    }

    // Deadline.

    @Test
    fun `the root and the instances share one deadline for closing and draining`() = runTest {
        val steps = Steps(close = { delay(1.seconds) }, drain = { delay(3.seconds) })
        val spec = spec(
            explicit(agent(drain = { delay(2.seconds) }), chan(steps)),
            dataDir,
            twoInstances,
            shutdownGrace = 5.seconds,
            dispatcher = virtual(),
        )
        val runtime = startVirtually(spec)

        runtime.stop()
        val problems = runtime.join().problems

        assertEquals(
            listOf(
                Problem(
                    RuntimeProblemKind.SHUTDOWN,
                    "Draining Bot (plugin chan) in channel instance container 'chan:home' was cancelled: the " +
                        "shutdown grace of 5s ran out.",
                    "chan",
                ),
                Problem(
                    RuntimeProblemKind.SHUTDOWN,
                    "Skipped draining Bot (plugin chan) in channel instance container 'chan:work': the shutdown " +
                        "grace of 5s had run out.",
                    "chan",
                ),
            ),
            problems,
        )
        assertEquals(5_000, currentTime)
        assertEquals(listOf("drain agent", "drain home"), events.all().filter { it.startsWith("drain") })
    }

    // Failures.

    @Test
    fun `an instance that fails to start fails the run at START, and what was built is torn down`() {
        val error = channels(Steps(start = { check(it.id != work) { "start ${it.id.name} failed" } })).startFailure()

        assertEquals(StartStage.START, error.stage)
        assertEquals(
            "Cannot start runtime 'test': stage START failed: channel instance container 'chan:work': " +
                "java.lang.IllegalStateException: start work failed",
            error.message,
        )
        assertEquals(
            listOf(
                "create agent", "create work", "create home",
                "start agent", "start work",
                "drain agent",
                "stop agent",
                "destroy home", "destroy work", "destroy agent",
            ),
            events.all(),
        )
    }

    @Test
    fun `an instance that fails to open fails the run at OPEN, after the instances opened before it close`() {
        val error = channels(Steps(open = { check(it.id != home) { "open ${it.id.name} failed" } })).startFailure()

        assertEquals(StartStage.OPEN, error.stage)
        assertContains(error.message!!, "stage OPEN failed: channel instance container 'chan:home': ")
        assertEquals(
            listOf(
                "open agent", "open work", "open home",
                "close work", "close agent",
                "drain agent", "drain home", "drain work",
                "stop home", "cancelled home", "stop work", "cancelled work", "stop agent", "cancelled agent",
                "destroy home", "destroy work", "destroy agent",
            ),
            events.all().dropWhile { !it.startsWith("open") },
        )
    }

    @Test
    fun `an instance that cannot be created fails the GRAPH stage and destroys the instances created before it`() {
        val error = channels(Steps(construct = { check(it.id != home) { "no ${it.id.name}" } })).startFailure()

        assertEquals(StartStage.GRAPH, error.stage)
        assertEquals(listOf(DiProblemKind.CREATION_FAILED), error.problems.map { it.kind })
        assertContains(error.message!!, "Cannot create ${key<Channel>()} with Bot (plugin chan): ")
        assertEquals(listOf("create agent", "create work", "destroy work", "destroy agent"), events.all())
    }

    @Test
    fun `a config problem that an instance finds when created or started is invalid config naming its container`() {
        val token = { id: ChannelInstanceId ->
            ConfigException("plugins.chan.instances.${id.name}.token", "the bot refuses the token")
        }
        val created = channels(Steps(construct = { if (it.id == home) throw token(it.id) })).startFailure()
        val started = channels(Steps(start = { if (it.id == work) throw token(it.id) })).startFailure()

        assertEquals(listOf(StartStage.GRAPH, StartStage.START), listOf(created.stage, started.stage))
        assertEquals(
            listOf(
                Problem(
                    RuntimeProblemKind.INVALID_CONFIG,
                    "channel instance container 'chan:home': Invalid config at 'plugins.chan.instances.home.token': " +
                        "the bot refuses the token",
                    "chan",
                ),
                Problem(
                    RuntimeProblemKind.INVALID_CONFIG,
                    "channel instance container 'chan:work': Invalid config at 'plugins.chan.instances.work.token': " +
                        "the bot refuses the token",
                    "chan",
                ),
            ),
            created.problems + started.problems,
        )
    }

    @Test
    fun `what fails while an instance stops is reported naming its container`() {
        val termination = channels(Steps(stop = { check(it.id != work) { "stop ${it.id.name} failed" } })).execute()

        assertEquals(
            listOf(
                Problem(
                    RuntimeProblemKind.SHUTDOWN,
                    "Stopping Bot (plugin chan) in channel instance container 'chan:work' failed: " +
                        "java.lang.IllegalStateException: stop work failed",
                    "chan",
                ),
            ),
            termination.problems,
        )
    }

    @Test
    fun `coroutines of an instance still running at the deadline are reported once, naming the instance`() {
        val release = CompletableDeferred<Unit>()
        val straggler = Steps(
            open = { instance ->
                if (instance.id == work) instance.scope.launch { withContext(NonCancellable) { release.await() } }
            },
        )

        val termination = channels(straggler, shutdownGrace = 0.seconds).execute()
        release.complete(Unit)

        assertEquals(
            listOf(
                Problem(
                    RuntimeProblemKind.SHUTDOWN,
                    "Coroutines of channel instance chan:work (plugin chan) were still running: the shutdown grace " +
                        "of 0s ran out.",
                    "chan",
                ),
            ),
            termination.problems.filter { it.message.startsWith("Coroutines of") },
        )
    }

    // Control.

    @Test
    fun `a controlled stop closes, drains, stops and destroys one instance while the others and the root run on`() {
        val ada = ChatUser(UserAddress(work, "7"), "Ada", null, isBot = false, isOperator = true)
        val away = ChannelInstanceId(ChannelType("chan"), "away")

        val lines = logged {
            channels().execute {
                val control = services.get<ChannelControl>()
                val directory = services.resolver().get(key<ChannelDirectory>())

                assertEquals(InstanceState.OPEN, control.state(work))
                assertTrue(control.stop(work, ada))

                assertEquals(InstanceState.STOPPED, control.state(work))
                assertEquals(InstanceState.OPEN, control.state(home))
                assertNull(directory.channel(work))
                assertNotNull(directory.channel(home))
                assertFalse(control.stop(work, null))
                assertNull(control.state(away))
                assertFalse(control.stop(away, null))
            }
        }

        assertEquals(
            listOf(
                "close work", "drain work", "stop work", "cancelled work", "destroy work",
                "close home", "close agent",
                "drain agent", "drain home",
                "stop home", "cancelled home", "stop agent", "cancelled agent",
                "destroy home", "destroy agent",
            ),
            events.all().dropWhile { it != "open home" }.drop(1),
        )
        assertContains(lines, "INFO test: stopping channel instance chan:work at the request of chan:work@7")
        assertContains(lines, "INFO test: stopped channel instance chan:work")
    }

    @Test
    fun `what fails while one instance stops is logged, and the run goes on`() {
        val steps = Steps(close = { check(it.id != work) { "close ${it.id.name} failed" } })

        val lines = logged {
            val termination = channels(steps).execute {
                val control = services.get<ChannelControl>()

                assertTrue(control.stop(work, null))
                assertEquals(InstanceState.STOPPED, control.state(work))
            }

            assertEquals(emptyList(), termination.problems)
        }

        assertContains(
            lines,
            "WARN test: Closing Bot (plugin chan) in channel instance container 'chan:work' failed: " +
                "java.lang.IllegalStateException: close work failed",
        )
        assertContains(events.all(), "destroy work")
    }

    @Test
    fun `the runtime's stop waits for an instance that is stopping alone`() {
        val draining = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val steps = Steps(
            drain = {
                if (it.id == work) {
                    draining.complete(Unit)
                    release.await()
                }
            },
        )
        val runtime = channels(steps).started()
        val control = runtime.services.get<ChannelControl>()

        runBlocking {
            val stopped = async(Dispatchers.Default) { control.stop(work, null) }
            draining.await()
            runtime.stop()
            delay(200)
            release.complete(Unit)
            assertTrue(stopped.await())
        }
        runtime.terminated()

        assertEquals(
            listOf("drain work", "stop work", "cancelled work", "destroy work", "close home", "close agent"),
            events.all().dropWhile { it != "drain work" }.take(6),
        )
        assertEquals(InstanceState.STOPPED, control.state(home))
    }

    // Channel rules.

    @Test
    fun `channel contributions other than one named channel-instance-scoped channel per plugin fail CONFIG alone`() {
        val plugins = explicit(
            TestIndex("loose", bindings = listOf(plain("loose", "Loose", CHANNEL))),
            TestIndex("odd", bindings = listOf(plain("odd", "Odd", CHANNEL, "Odd_Type"))),
            TestIndex("solo", bindings = listOf(plain("solo", "Solo", SINGLETON, "solo"))),
            TestIndex(
                "twin",
                bindings = listOf(plain("twin", "First", CHANNEL, "twin"), plain("twin", "Second", CHANNEL, "twin")),
            ),
        )

        val instances = """{"plugins": {"loose": {"instances": {"work": {}}}, "twin": {"instances": {"work": {}}}}}"""

        val error = spec(plugins, dataDir, instances).startFailure()

        assertEquals(StartStage.CONFIG, error.stage)
        assertEquals(
            listOf(
                Problem(
                    RuntimeProblemKind.CHANNEL_CONTRIBUTIONS,
                    "Unnamed channel: Loose (plugin loose) contributes a Channel without a name, which is its " +
                        "channel type.",
                    "loose",
                ),
                Problem(
                    RuntimeProblemKind.MALFORMED_CHANNEL_TYPE,
                    "Malformed channel type 'Odd_Type' of plugin 'odd', the name of its Channel Odd: a channel type " +
                        "is lowercase words of letters and digits, each starting with a letter, joined by single " +
                        "hyphens, such as \"telegram\". Rebuild the plugin with the Alexandrite KSP processor.",
                    "odd",
                ),
                Problem(
                    RuntimeProblemKind.CHANNEL_CONTRIBUTIONS,
                    "Singleton channel: Solo (plugin solo) contributes a Channel, which is channel-instance-scoped.",
                    "solo",
                ),
                Problem(
                    RuntimeProblemKind.CHANNEL_CONTRIBUTIONS,
                    "Several channels: plugin 'twin' contributes a Channel from First (plugin twin) and Second " +
                        "(plugin twin), but a channel plugin contributes exactly one.",
                    "twin",
                ),
            ),
            error.problems,
        )
    }

    @Test
    fun `the graph of a channel plugin is validated with its instance bindings although it has no instances`() {
        val dependencies = listOf(
            Dependency(key<ChannelInstance>(), DependencyKind.INSTANCE, "instance"),
            Dependency(key<BotConfig>(), DependencyKind.INSTANCE, "config"),
            Dependency(key<String>("token"), DependencyKind.INSTANCE, "token"),
        )
        val needy = binding(key<Channel>(), "chan", "Needy", CHANNEL, dependencies, multi = true, name = "chan") {
            TestChannel()
        }
        val plugins = explicit(TestIndex("chan", bindings = listOf(needy), sections = listOf(botSection)))

        val error = spec(plugins, dataDir, """{"plugins": {"chan": {}}}""").startFailure()

        assertEquals(StartStage.GRAPH, error.stage)
        assertEquals(listOf(DiProblemKind.MISSING), error.problems.map { it.kind })
        assertContains(error.problems.single().message, "nothing binds ${key<String>("token")}, which Needy")
    }

    @Test
    fun `two enabled plugins of one channel type fail CONFIG`() {
        val twins = explicit(chan(), TestIndex("other", bindings = listOf(plain("other", "Other", CHANNEL, "chan"))))

        val duplicate = spec(twins, dataDir, twoInstances).startFailure()

        assertEquals(StartStage.CONFIG, duplicate.stage)
        assertEquals(
            listOf(
                Problem(
                    RuntimeProblemKind.DUPLICATE_CHANNEL_TYPE,
                    "Duplicate channel type 'chan': plugins 'chan' and 'other' declare it. Switch all but one of " +
                        "them off.",
                    null,
                ),
            ),
            duplicate.problems,
        )
    }

    private fun plain(plugin: String, origin: String, scope: Scope, name: String? = null): Binding<Channel> =
        binding(key<Channel>(), plugin, origin, scope, multi = true, name = name) { TestChannel() }

    private open class TestChannel : Channel {
        override suspend fun capabilities(chat: ChatAddress): ChannelCapabilities =
            ChannelCapabilities.builder().build()

        override suspend fun partsNeeded(chat: ChatAddress, text: String, markup: Markup): Int = 1

        override suspend fun openReply(request: ReplyRequest): ReplySink = error("no replies")

        override suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery =
            Delivery.Delivered(emptyList())
    }
}

private val CHANNEL = Scope.CHANNEL_INSTANCE

private val SINGLETON = Scope.SINGLETON
