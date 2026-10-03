package org.foedusprogramme.alexandrite.runtime

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.foedusprogramme.alexandrite.runtime.RuntimeEvent.Closed
import org.foedusprogramme.alexandrite.runtime.RuntimeEvent.PluginsResolved
import org.foedusprogramme.alexandrite.runtime.RuntimeEvent.StartFailed
import org.foedusprogramme.alexandrite.runtime.RuntimeEvent.Started
import org.foedusprogramme.alexandrite.sdk.di.Binding
import org.foedusprogramme.alexandrite.sdk.di.Container
import org.foedusprogramme.alexandrite.sdk.di.DiProblemKind
import org.foedusprogramme.alexandrite.sdk.di.PluginBindings
import org.foedusprogramme.alexandrite.sdk.di.binding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.hook.FailurePolicy
import org.foedusprogramme.alexandrite.sdk.hook.Hook
import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
import org.foedusprogramme.alexandrite.sdk.hook.HookEffect
import org.foedusprogramme.alexandrite.sdk.hook.Hooks
import org.foedusprogramme.alexandrite.sdk.hook.InterceptorHook
import org.foedusprogramme.alexandrite.sdk.hook.InterceptorPoint
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RuntimeTest {
    @TempDir
    lateinit var dataDir: Path

    private val events = Events()
    private val recorder = Recorder()

    private val intercepted =
        InterceptorPoint<String>("test.intercepted", setOf(HookEffect.REPLACE), FailurePolicy.FAIL_OPEN)

    private class Failing(override val point: InterceptorPoint<String>) : InterceptorHook<String> {
        override suspend fun intercept(payload: String): HookDecision<String> = error("hook failed")
    }

    private fun core(vararg services: Binding<*>) = explicit(TestIndex("core", bindings = services.toList()))

    private fun startingIn(runtime: AlexandriteRuntime): Pair<Thread, () -> Throwable?> {
        var thrown: Throwable? = null
        val starter = thread { thrown = runCatching { runBlocking { runtime.start() } }.exceptionOrNull() }
        return starter to { thrown }
    }

    // Plugins.

    @Test
    fun `duplicate plugin ids fail the PLUGINS stage`() {
        val agent = AgentIndex::class.java.name

        val error = runtime(builtIn(dataDir, AgentIndex::class) + AgentIndex(), dataDir).startFailure()

        assertEquals(StartStage.PLUGINS, error.stage)
        assertEquals(
            listOf(
                Problem(
                    DiProblemKind.DUPLICATE_PLUGIN,
                    "Duplicate plugin 'alexandrite-agent': the plugin set holds $agent and $agent. " +
                        "Keep only one of them.",
                    "alexandrite-agent",
                    null,
                ),
            ),
            error.problems,
        )
    }

    @Test
    fun `a plugin that is not built in needs a well-formed, unreserved id and its own config root`() {
        val plugins = explicit(
            TestIndex("alexandrite-weather"),
            TestIndex("Weather_2"),
            TestIndex("rain", "rain"),
            TestIndex("a-1"),
            AgentIndex(),
        )

        val error = runtime(plugins, dataDir).startFailure()

        assertEquals(StartStage.PLUGINS, error.stage)
        assertEquals(
            listOf(
                RuntimeProblemKind.RESERVED_NAME to "alexandrite-weather",
                RuntimeProblemKind.MALFORMED_NAME to "Weather_2",
                RuntimeProblemKind.WRONG_ROOT to "rain",
                RuntimeProblemKind.MALFORMED_NAME to "a-1",
            ),
            error.problems.map { it.kind to it.plugin },
        )
        assertContains(error.problems[0].message, "ids starting with 'alexandrite-' belong to built-in plugins")
        assertEquals(
            "Wrong config root 'rain' of plugin 'rain' (${TestIndex::class.java.name}): a plugin that is not built " +
                "in reads 'plugins.rain'. Rebuild it with the Alexandrite KSP processor.",
            error.problems[2].message,
        )
    }

    @Test
    fun `a built-in index whose id or config root differs from its row fails the PLUGINS stage`() {
        val error = runtime(builtIn(dataDir, RenamedIndex::class) + MovedIndex(), dataDir).startFailure()

        assertEquals(StartStage.PLUGINS, error.stage)
        assertEquals(
            listOf(
                "Mismatched built-in index ${RenamedIndex::class.java.name}: it declares plugin " +
                    "'alexandrite-channel-renamed' at config root 'channels.discord', but the built-in list has " +
                    "plugin 'alexandrite-channel-discord' at 'channels.discord'. " +
                    "Use the plugin jar built with this runtime.",
                "Mismatched built-in index ${MovedIndex::class.java.name}: it declares plugin " +
                    "'alexandrite-channel-discord' at config root 'channels.moved', but the built-in list has " +
                    "plugin 'alexandrite-channel-discord' at 'channels.discord'. " +
                    "Use the plugin jar built with this runtime.",
            ),
            error.problems.map { it.message },
        )
        assertEquals(List(2) { RuntimeProblemKind.MISMATCHED_INDEX }, error.problems.map { it.kind })
    }

    @Test
    fun `a listed index that cannot be loaded fails the PLUGINS stage`() {
        val error = runtime(builtIn(dataDir, AgentIndex::class, BrokenIndex::class), dataDir).startFailure()

        assertEquals(StartStage.PLUGINS, error.stage)
        assertEquals(
            listOf(RuntimeProblemKind.BROKEN_INDEX to "alexandrite-broken"),
            error.problems.map { it.kind to it.plugin },
        )
    }

    @Test
    fun `an index that two jars ship fails the PLUGINS stage`() {
        val plugins = classPath(dataDir, Jar(names(AgentIndex::class)), Jar(names(AgentIndex::class)))
            .use { PluginSet.builtIn(it) }

        val error = runtime(plugins, dataDir).startFailure()

        assertEquals(StartStage.PLUGINS, error.stage)
        assertEquals(
            listOf(
                Problem(
                    RuntimeProblemKind.DUPLICATE_INDEX,
                    "Duplicate index ${AgentIndex::class.java.name} of plugin 'alexandrite-agent': several service " +
                        "files list it, so two jars on the class path ship it. Keep only one of them.",
                    "alexandrite-agent",
                    null,
                ),
            ),
            error.problems,
        )
    }

    // Start.

    @Test
    fun `a failing start closes the container and fails the START stage`() {
        val plugins = core(
            service("a", "core", events),
            service("b", "core", events, listOf("a"), onStart = { error("start b failed") }),
            service("c", "core", events, listOf("b")),
        )

        runtime(plugins, dataDir).use { runtime ->
            val error = runtime.startFailure()

            assertEquals(StartStage.START, error.stage)
            assertEquals(emptyList(), error.problems)
            assertEquals(
                "Cannot start runtime 'test': stage START failed: java.lang.IllegalStateException: start b failed",
                error.message,
            )
            assertFailsWith<IllegalStateException> { runtime.services }
        }
        assertEquals(
            listOf("create a", "create b", "create c", "start a", "start b", "close a", "close c", "close b"),
            events.all(),
        )
    }

    @Test
    fun `a start that outlasts the start timeout fails the START stage`() {
        val plugins = core(service("a", "core", events, onStart = hang))

        val error = runtime(plugins, dataDir, startTimeout = 50.milliseconds).use { it.startFailure() }

        assertEquals(StartStage.START, error.stage)
        assertNull(error.cause)
        assertEquals("Cannot start runtime 'test': stage START failed: not started within 50ms", error.message)
        assertEquals(listOf("create a", "start a", "close a"), events.all())
    }

    @Test
    fun `the caller's own timeout cancels the start without passing for the runtime's`() {
        val plugins = core(service("a", "core", events, onStart = hang))

        runtime(plugins, dataDir, listener = recorder).use { runtime ->
            assertFailsWith<TimeoutCancellationException> {
                runBlocking { withTimeout(100.milliseconds) { runtime.start() } }
            }
        }

        assertEquals(listOf("PluginsResolved", "StartFailed", "Closed"), recorder.names())
        val failed = recorder.events.filterIsInstance<StartFailed>().single().error
        assertEquals("Cannot start runtime 'test': stage START failed: cancelled", failed.message)
        assertEquals(listOf("create a", "start a", "close a"), events.all())
    }

    @Test
    fun `close during start cancels it, so no instance starts after close`() {
        val entered = CountDownLatch(1)
        val waiting: suspend () -> Unit = {
            entered.countDown()
            awaitCancellation()
        }
        val plugins = core(
            service("a", "core", events),
            service("b", "core", events, listOf("a"), onStart = waiting),
            service("c", "core", events, listOf("b")),
        )
        val runtime = runtime(plugins, dataDir, listener = recorder)
        val (starter, thrown) = startingIn(runtime)

        runtime.use {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            runtime.close()
            starter.join(5_000)
        }

        val error = assertNotNull(thrown() as? RuntimeStartException)
        assertEquals(StartStage.START, error.stage)
        assertEquals("Cannot start runtime 'test': stage START failed: closed while starting", error.message)
        assertEquals(
            listOf("create a", "create b", "create c", "start a", "start b", "close a", "close c", "close b"),
            events.all(),
        )
        assertEquals(listOf("PluginsResolved", "StartFailed", "Closed"), recorder.names())
        assertSame(error, recorder.events.filterIsInstance<StartFailed>().single().error)
        val late = assertFailsWith<IllegalStateException> { runtime.services }
        assertEquals("Runtime 'test' has no services: it is closed.", late.message)
    }

    @Test
    fun `close during assembly fails the start at the stage it reached`() {
        lateinit var runtime: AlexandriteRuntime
        val listener = RuntimeListener { event ->
            recorder.onEvent(event)
            if (event is PluginsResolved) runtime.close()
        }
        runtime = runtime(core(service("a", "core", events)), dataDir, listener = listener)

        val error = runtime.use { it.startFailure() }

        assertEquals(StartStage.GRAPH, error.stage)
        assertEquals("Cannot start runtime 'test': stage GRAPH failed: closed while starting", error.message)
        assertEquals(listOf("PluginsResolved", "StartFailed", "Closed"), recorder.names())
        assertEquals(listOf("create a", "close a"), events.all())
    }

    // Events.

    @Test
    fun `a runtime reports its plugins, its start and its close`() {
        val plugins = builtIn(dataDir, AgentIndex::class, TelegramIndex::class, HelloIndex::class)

        val loaded = runtime(plugins, dataDir, """{"plugins": {"weather": {}}}""", recorder).started().use {
            it.plugins
        }

        val agent = loaded(AgentIndex(), BuiltInLayer.AGENT)
        assertEquals(
            listOf(
                PluginsResolved(
                    loaded = listOf(agent),
                    disabled = listOf(
                        DisabledPlugin("alexandrite-channel-telegram", DisabledPlugin.Reason.NOT_CONFIGURED),
                    ),
                    unlisted = listOf(HelloIndex::class.java.name),
                    unknownPluginConfig = listOf("plugins.weather"),
                ),
                Started,
                Closed,
            ),
            recorder.events,
        )
        assertEquals(listOf(agent), loaded)
    }

    private class FailedStart(
        val stage: StartStage,
        val plugins: PluginSet,
        val config: String,
        val events: List<String>,
    )

    @Test
    fun `a failed start is reported once with its error`() {
        val cases = listOf(
            FailedStart(
                StartStage.PLUGINS,
                explicit(TestIndex("alexandrite-weather")),
                "{}",
                listOf("StartFailed"),
            ),
            FailedStart(StartStage.CONFIG, explicit(AgentIndex()), """{"weather": {}}""", listOf("StartFailed")),
            FailedStart(
                StartStage.GRAPH,
                core(service("a", "core", dependencies = listOf("b"))),
                "{}",
                listOf("PluginsResolved", "StartFailed"),
            ),
            FailedStart(
                StartStage.START,
                core(service("a", "core", onStart = { error("start a failed") })),
                "{}",
                listOf("PluginsResolved", "StartFailed"),
            ),
        )

        for (case in cases) {
            val recorder = Recorder()

            runtime(case.plugins, dataDir, case.config, recorder).use { runtime ->
                val error = runtime.startFailure()

                assertEquals(case.stage, error.stage)
                assertEquals(case.events, recorder.names(), "${case.stage}")
                assertEquals(StartFailed(error), recorder.events.last())
            }
        }
    }

    @Test
    fun `whenever close comes, start ends in one outcome before the one Closed`() {
        val outcomes = setOf(
            listOf("Closed"),
            listOf("StartFailed", "Closed"),
            listOf("PluginsResolved", "StartFailed", "Closed"),
            listOf("PluginsResolved", "Started", "Closed"),
        )

        repeat(40) { round ->
            val recorder = Recorder()
            val runtime = runtime(core(service("a", "core")), dataDir, listener = recorder)
            val (starter, _) = startingIn(runtime)

            Thread.sleep(round % 4L)
            runtime.close()
            starter.join(5_000)

            assertContains(outcomes, recorder.names(), "round $round")
        }
    }

    @Test
    fun `a listener that throws is logged at WARN and stops nothing`() {
        lateinit var plugins: List<LoadedPlugin>

        val lines = logged {
            runtime(explicit(AgentIndex()), dataDir, listener = { error("listener failed") }).started().use {
                plugins = it.plugins
            }
        }

        assertEquals(listOf("alexandrite-agent"), plugins.map { it.info.id })
        assertEquals(
            listOf("PluginsResolved", "Started", "Closed"),
            lines.filter { it.startsWith("WARN test: listener failed on ") }
                .map { it.removePrefix("WARN test: listener failed on ").substringBefore('(') },
        )
    }

    @Test
    fun `a listener may close the runtime when it starts`() {
        lateinit var runtime: AlexandriteRuntime
        val listener = RuntimeListener { event ->
            recorder.onEvent(event)
            if (event == Started) runtime.close()
        }
        runtime = runtime(core(service("a", "core", events)), dataDir, listener = listener)

        runtime.use { it.started() }

        assertEquals(listOf("PluginsResolved", "Started", "Closed"), recorder.names())
        assertEquals(listOf("create a", "start a", "close a"), events.all())
    }

    // Services.

    @Test
    fun `services resolve only host API types`() {
        runtime(core(service("a", "core"), probe("core")), dataDir).started().use { runtime ->
            assertEquals(emptyMap(), runtime.services.get(key<Probe>()).values)
            assertNull(runtime.services.getOrNull(key<Probe>("other")))
            for (key in listOf(key<Clock>(), key<Service>("a"))) {
                val error = assertFailsWith<IllegalArgumentException> { runtime.services.getOrNull(key) }
                assertContains(
                    error.message!!,
                    "is not marked @HostApi. Mark it @HostApi, or expose it through a @HostApi type.",
                )
            }
        }
    }

    @Test
    fun `services are available from start until close`() {
        val runtime = runtime(core(probe("core")), dataDir)

        val early = assertFailsWith<IllegalStateException> { runtime.services }
        val services = runtime.started().use { it.services }

        assertEquals("Runtime 'test' has no services until start() returns.", early.message)
        for (late in listOf({ runtime.services }, { services.get(key<Probe>()) })) {
            val error = assertFailsWith<IllegalStateException> { late() }
            assertEquals("Runtime 'test' has no services: it is closed.", error.message)
        }
    }

    @Test
    fun `a container closed under a running call fails it like a closed runtime`() {
        val container = Container.build(listOf(PluginBindings("core", listOf(probe("core")))))
        container.close()
        var checks = 0
        val services = RuntimeServices(container) { if (checks++ == 0) null else "Runtime 'test' is closed." }

        val error = assertFailsWith<IllegalStateException> { services.get(key<Probe>()) }

        assertEquals("Runtime 'test' is closed.", error.message)
    }

    @Test
    fun `the plugins are known once resolved and the message says why they are not`() {
        val fresh = runtime(explicit(AgentIndex()), dataDir)
        val failed = runtime(explicit(TestIndex("alexandrite-weather")), dataDir).apply { startFailure() }
        val closed = runtime(explicit(AgentIndex()), dataDir).apply { close() }
        val unbuilt = runtime(core(service("a", "core", dependencies = listOf("b"))), dataDir).apply { startFailure() }

        val messages = listOf(fresh, failed, closed).map { runtime ->
            assertFailsWith<IllegalStateException> { runtime.plugins }.message
        }

        assertEquals(
            listOf(
                "Runtime 'test' has not resolved its plugins: call start() first.",
                "Runtime 'test' failed to start before resolving its plugins.",
                "Runtime 'test' was closed before resolving its plugins.",
            ),
            messages,
        )
        assertEquals(listOf("core"), unbuilt.plugins.map { it.info.id })
    }

    // Lifecycle.

    @Test
    fun `close closes the instances in reverse creation order, once`() {
        val plugins = explicit(
            TestIndex("store", bindings = listOf(service("db", "store", events))),
            TestIndex("turns", bindings = listOf(service("worker", "turns", events, listOf("db")))),
        )

        runtime(plugins, dataDir, listener = recorder).started().use { it.close() }

        assertEquals(
            listOf("create db", "create worker", "start db", "start worker", "close worker", "close db"),
            events.all(),
        )
        assertEquals(listOf("PluginsResolved", "Started", "Closed"), recorder.names())
    }

    @Test
    fun `a runtime starts once and never after closing or failing`() {
        runtime(explicit(AgentIndex()), dataDir).started().use { started ->
            val failed = runtime(explicit(TestIndex("alexandrite-weather")), dataDir).apply { startFailure() }
            val closed = runtime(explicit(AgentIndex()), dataDir, listener = recorder).apply { close() }

            val messages = listOf(started, failed, closed).map { runtime ->
                assertFailsWith<IllegalStateException> { runBlocking { runtime.start() } }.message
            }

            assertEquals(
                listOf("it has started", "it failed to start", "it is closed").map {
                    "Cannot start runtime 'test': $it."
                },
                messages,
            )
            assertEquals(listOf("Closed"), recorder.names())
        }
    }

    // Logging.

    @Test
    fun `resolution is logged at INFO, unlisted indexes and unknown plugin config at WARN`() {
        val plugins = builtIn(dataDir, AgentIndex::class, TelegramIndex::class, HelloIndex::class)

        val lines = logged { runtime(plugins, dataDir, """{"plugins": {"weather": {}}}""").started().close() }

        assertEquals(
            listOf(
                "WARN test: not loading ${HelloIndex::class.java.name}: the index is neither built in nor added to " +
                    "the plugin set",
                "WARN test: ignoring the config at 'plugins.weather': no plugin of the plugin set reads it",
                "INFO test: loading plugins [alexandrite-agent], disabled [alexandrite-channel-telegram " +
                    "(NOT_CONFIGURED)]",
            ),
            lines,
        )
    }

    @Test
    fun `a failing hook is logged at WARN`() {
        val hook = binding(key<Hook>(), "core", "Failing", multi = true) { Failing(intercepted) }

        val lines = logged {
            runtime(core(hook, probe("core", "hooks" to key<Hooks>())), dataDir).started().use { runtime ->
                val hooks = runtime.services.get(key<Probe>()).values.getValue("hooks") as Hooks
                runBlocking { hooks.fire(intercepted, "payload") }
            }
        }

        assertContains(
            lines,
            "WARN test: hook ${Failing::class.java.name} failed at 'test.intercepted': " +
                "Threw(error=java.lang.IllegalStateException: hook failed)",
        )
    }
}
