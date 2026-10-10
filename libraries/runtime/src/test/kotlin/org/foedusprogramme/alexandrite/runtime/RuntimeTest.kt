package org.foedusprogramme.alexandrite.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.runtime.RuntimeEvent.PluginsResolved
import org.foedusprogramme.alexandrite.runtime.RuntimeEvent.Ready
import org.foedusprogramme.alexandrite.runtime.RuntimeEvent.StartFailed
import org.foedusprogramme.alexandrite.runtime.RuntimeEvent.Started
import org.foedusprogramme.alexandrite.runtime.RuntimeEvent.Stopped
import org.foedusprogramme.alexandrite.runtime.RuntimeEvent.Stopping
import org.foedusprogramme.alexandrite.runtime.RuntimeEvent.UnlistedIndexes
import org.foedusprogramme.alexandrite.runtime.plugin.BuiltInLayer
import org.foedusprogramme.alexandrite.runtime.plugin.DisabledPlugin
import org.foedusprogramme.alexandrite.runtime.plugin.PluginSet
import org.foedusprogramme.alexandrite.sdk.AlexandriteSdk
import org.foedusprogramme.alexandrite.sdk.config.ConfigException
import org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec
import org.foedusprogramme.alexandrite.sdk.config.Secret
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.di.container.Container
import org.foedusprogramme.alexandrite.sdk.di.container.Dependency
import org.foedusprogramme.alexandrite.sdk.di.container.DependencyKind
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind
import org.foedusprogramme.alexandrite.sdk.di.container.PluginBindings
import org.foedusprogramme.alexandrite.sdk.di.container.Resolver
import org.foedusprogramme.alexandrite.sdk.di.container.binding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.hook.FailurePolicy
import org.foedusprogramme.alexandrite.sdk.hook.Hook
import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
import org.foedusprogramme.alexandrite.sdk.hook.HookEffect
import org.foedusprogramme.alexandrite.sdk.hook.Hooks
import org.foedusprogramme.alexandrite.sdk.hook.InterceptorHook
import org.foedusprogramme.alexandrite.sdk.hook.InterceptorPoint
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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

    // Plugins.

    @Test
    fun `duplicate plugin ids fail the PLUGINS stage`() {
        val agent = AgentIndex::class.java.name

        val error = spec(builtIn(dataDir, AgentIndex::class) + AgentIndex(), dataDir).startFailure()

        assertEquals(StartStage.PLUGINS, error.stage)
        assertEquals(
            listOf(
                Problem(
                    RuntimeProblemKind.DUPLICATE_PLUGIN,
                    "Duplicate plugin 'alexandrite-agent': the plugin set holds $agent and $agent. " +
                        "Keep only one of them.",
                    "alexandrite-agent",
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

        val error = spec(plugins, dataDir).startFailure()

        assertEquals(StartStage.PLUGINS, error.stage)
        assertEquals(
            listOf(
                RuntimeProblemKind.RESERVED_ID to "alexandrite-weather",
                RuntimeProblemKind.MALFORMED_ID to "Weather_2",
                RuntimeProblemKind.WRONG_ROOT to "rain",
                RuntimeProblemKind.MALFORMED_ID to "a-1",
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
        val error = spec(builtIn(dataDir, RenamedIndex::class) + MovedIndex(), dataDir).startFailure()

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
        val error = spec(builtIn(dataDir, AgentIndex::class, BrokenIndex::class), dataDir).startFailure()

        assertEquals(StartStage.PLUGINS, error.stage)
        assertEquals(
            listOf(RuntimeProblemKind.BROKEN_INDEX to "alexandrite-broken"),
            error.problems.map { it.kind to it.plugin },
        )
    }

    @Test
    fun `an index that two jars ship fails the PLUGINS stage`() {
        val plugins = classPath(dataDir, Jar(names(AgentIndex::class)), Jar(names(AgentIndex::class)))
            .use { PluginSet.builtIn(it, TEST_BUILT_INS) }

        val error = spec(plugins, dataDir).startFailure()

        assertEquals(StartStage.PLUGINS, error.stage)
        assertEquals(
            listOf(
                Problem(
                    RuntimeProblemKind.DUPLICATE_INDEX,
                    "Duplicate index ${AgentIndex::class.java.name} of plugin 'alexandrite-agent': several service " +
                        "files list it, so two jars on the class path ship it. Keep only one of them.",
                    "alexandrite-agent",
                ),
            ),
            error.problems,
        )
    }

    // Start.

    @Test
    fun `a failing start stops what started, destroys the container and fails the START stage`() {
        val plugins = core(
            service("a", "core", events),
            service("b", "core", events, listOf("a"), onStart = { error("start b failed") }),
            service("c", "core", events, listOf("b")),
        )

        val error = spec(plugins, dataDir).startFailure()

        assertEquals(StartStage.START, error.stage)
        assertEquals(emptyList(), error.problems)
        assertEquals(
            "Cannot start runtime 'test': stage START failed: java.lang.IllegalStateException: start b failed",
            error.message,
        )
        assertEquals(
            listOf(
                "create a", "create b", "create c",
                "start a", "start b",
                "stop a",
                "destroy c", "destroy b", "destroy a",
            ),
            events.all(),
        )
    }

    @Test
    fun `a config problem that a component finds when created fails the GRAPH stage as invalid config`() {
        val model = ConfigException("agent.agents.coder.model", "no endpoint 'local'")
        val plugins = core(
            service("a", "core", events),
            binding(key<Service>("b"), "core", "b", dependencies = emptyList()) { throw model },
        )

        val error = spec(plugins, dataDir).startFailure()

        assertEquals(StartStage.GRAPH, error.stage)
        assertEquals(
            listOf(
                Problem(
                    RuntimeProblemKind.INVALID_CONFIG,
                    "Invalid config at 'agent.agents.coder.model': no endpoint 'local'",
                    "core",
                ),
            ),
            error.problems,
        )
        assertSame(model, error.cause?.cause)
        assertEquals(
            "Cannot start runtime 'test': stage GRAPH failed (1 problem):\n" +
                "- Invalid config at 'agent.agents.coder.model': no endpoint 'local'",
            error.message,
        )
        assertEquals(listOf("create a", "destroy a"), events.all())
    }

    @Test
    fun `a config problem that a component finds when started fails the START stage as invalid config`() {
        val file = ConfigException("agent.agents.coder.instructionFiles", "agents/coder/PERSONA.md is missing")
        val plugins = core(service("a", "core", events), service("b", "core", events, onStart = { throw file }))

        val error = spec(plugins, dataDir).startFailure()

        assertEquals(StartStage.START, error.stage)
        assertEquals(
            listOf(Problem(RuntimeProblemKind.INVALID_CONFIG, file.message!!, null)),
            error.problems,
        )
        assertSame(file, error.cause)
        assertEquals(listOf("create a", "create b", "start a", "start b", "stop a"), events.all().take(5))
    }

    @Test
    fun `a start that outlasts the start timeout fails the START stage`() = runTest {
        val plugins = core(service("a", "core", events, onStart = hang))
        val spec = spec(plugins, dataDir, startTimeout = 200.milliseconds, dispatcher = virtual())

        val error = assertFailsWith<RuntimeStartException> { AlexandriteRuntime.start(spec) }

        assertEquals(StartStage.START, error.stage)
        assertNull(error.cause)
        assertEquals("Cannot start runtime 'test': stage START failed: not started within 200ms", error.message)
        assertEquals(listOf("create a", "start a", "destroy a"), events.all())
    }

    @Test
    fun `the caller's own timeout stops the start without passing for the runtime's`() = runTest {
        val spec =
            spec(
                core(service("a", "core", events, onStart = hang)),
                dataDir,
                listener = recorder,
                dispatcher = virtual(),
            )

        assertFailsWith<TimeoutCancellationException> { withTimeout(100.milliseconds) { AlexandriteRuntime.run(spec) } }

        assertEquals(listOf("PluginsResolved", "Stopping", "Stopped"), recorder.names())
        assertEquals(requested(RUN_CANCELLED), recorder.termination())
        assertEquals(listOf("create a", "start a", "destroy a"), events.all())
    }

    // Events.

    @Test
    fun `a runtime reports its plugins, its start and its stop`() {
        val plugins = builtIn(dataDir, AgentIndex::class, TelegramIndex::class, HelloIndex::class)

        spec(plugins, dataDir, """{"plugins": {"weather": {}}}""", recorder).execute()

        val agent = loaded(AgentIndex(), BuiltInLayer.AGENT)
        assertEquals(
            listOf(
                UnlistedIndexes(listOf(HelloIndex::class.java.name)),
                PluginsResolved(
                    loaded = listOf(agent),
                    disabled = listOf(
                        DisabledPlugin("alexandrite-channel-telegram", DisabledPlugin.Reason.NOT_CONFIGURED),
                    ),
                    unknownPluginConfig = listOf("plugins.weather"),
                ),
                Started,
                Ready,
                Stopping(BLOCK_RETURNED),
                Stopped(requested(BLOCK_RETURNED)),
            ),
            recorder.events,
        )
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

            val error = spec(case.plugins, dataDir, case.config, recorder).startFailure()

            assertEquals(case.stage, error.stage)
            assertEquals(case.events, recorder.names(), "${case.stage}")
            assertEquals(StartFailed(error), recorder.events.last())
        }
    }

    @Test
    fun `unlisted indexes are reported even when the plugin set fails its checks`() {
        val plugins = builtIn(dataDir, HelloIndex::class) + TestIndex("alexandrite-weather")
        val recorder = Recorder()

        spec(plugins, dataDir, listener = recorder).startFailure()

        assertEquals(listOf("UnlistedIndexes", "StartFailed"), recorder.names())
        assertEquals(UnlistedIndexes(listOf(HelloIndex::class.java.name)), recorder.events.first())
    }

    @Test
    fun `a plugin compiled against another plugin API fails the PLUGINS stage`() {
        val newer = object : TestIndex("newer") {
            override val info =
                PluginInfo("newer", "newer", "1.0", "", AlexandriteSdk.API_VERSION + 1, emptyList(), "x")
        }
        val older = object : TestIndex("older") {
            override val info =
                PluginInfo("older", "older", "1.0", "", AlexandriteSdk.API_VERSION - 1, emptyList(), "y")
        }

        val error = spec(explicit(newer, older), dataDir).startFailure()

        assertEquals(StartStage.PLUGINS, error.stage)
        assertEquals(
            listOf(RuntimeProblemKind.INCOMPATIBLE_SDK to "newer", RuntimeProblemKind.INCOMPATIBLE_SDK to "older"),
            error.problems.map {
                it.kind to it.plugin
            },
        )
        assertContains(error.problems.first().message, "compiled against version ${AlexandriteSdk.API_VERSION + 1}")
    }

    @Test
    fun `a listener that throws is logged at WARN and stops nothing`() {
        val spec = spec(explicit(AgentIndex()), dataDir, listener = { error("listener failed") })
        lateinit var termination: Termination

        val lines = logged { termination = spec.execute() }

        assertEquals(requested(BLOCK_RETURNED), termination)
        assertEquals(
            listOf("PluginsResolved", "Started", "Ready", "Stopping", "Stopped"),
            lines.filter { it.startsWith("WARN test: listener failed on ") }
                .map { it.removePrefix("WARN test: listener failed on ").substringBefore('(') },
        )
    }

    // Services.

    @Test
    fun `services resolve only host API types`() {
        spec(core(service("a", "core"), probe("core")), dataDir).execute {
            assertEquals(emptyMap(), services.get(key<Probe>()).values)
            assertEquals(emptyMap(), services.get<Probe>().values)
            assertNull(services.getOrNull(key<Probe>("other")))
            assertNull(services.getOrNull<Probe>("other"))
            for (key in listOf(key<Clock>(), key<Service>("a"))) {
                val error = assertFailsWith<IllegalArgumentException> { services.getOrNull(key) }
                assertContains(
                    error.message!!,
                    "is not marked @HostApi. Mark it @HostApi, or expose it through a @HostApi type.",
                )
            }
        }
    }

    @Test
    fun `the internal resolver of the services resolves every bound type until a stop is requested`() {
        val hook = binding(key<Hook>(), "core", "Failing", multi = true) { Failing(intercepted) }
        lateinit var resolver: Resolver
        lateinit var resolved: List<Any?>

        spec(core(service("a", "core"), hook), dataDir).execute {
            resolver = services.resolver()
            resolved = listOf(
                resolver.get(key<Service>("a")).name,
                resolver.getOrNull(key<Service>("b")),
                resolver.getAll(key<Hook>()).single().javaClass.simpleName,
                resolver.get(key<Clock>()).zone,
            )
        }

        assertEquals(listOf("a", null, "Failing", ZONE), resolved)
        val error = assertFailsWith<IllegalStateException> { resolver.get(key<Service>("a")) }
        assertEquals("Runtime 'test' has no services: a stop was requested.", error.message)
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

    // Lifecycle.

    @Test
    fun `a run stops and destroys the instances in reverse creation order`() {
        val plugins = explicit(
            TestIndex("store", bindings = listOf(service("db", "store", events))),
            TestIndex("turns", bindings = listOf(service("worker", "turns", events, listOf("db")))),
        )

        spec(plugins, dataDir, listener = recorder).execute()

        assertEquals(
            listOf(
                "create db",
                "create worker",
                "start db",
                "start worker",
                "stop worker",
                "stop db",
                "destroy worker",
                "destroy db",
            ),
            events.all(),
        )
        assertEquals(listOf("PluginsResolved", "Started", "Ready", "Stopping", "Stopped"), recorder.names())
    }

    // Logging.

    @Test
    fun `resolution is logged at INFO, unlisted indexes and unknown plugin config at WARN`() {
        val plugins = builtIn(dataDir, AgentIndex::class, TelegramIndex::class, HelloIndex::class)

        val lines = logged { spec(plugins, dataDir, """{"plugins": {"weather": {}}}""").execute() }

        assertEquals(
            listOf(
                "WARN test: not loading ${HelloIndex::class.java.name}: the index is neither built in nor added to " +
                    "the plugin set",
                "WARN test: ignoring the config at 'plugins.weather': no plugin of the plugin set reads it",
                "INFO test: loading plugins [alexandrite-agent], disabled [alexandrite-channel-telegram " +
                    "(NOT_CONFIGURED)]",
                "INFO test: stopping: $BLOCK_RETURNED",
                "INFO test: stopped",
            ),
            lines,
        )
    }

    @Test
    fun `a failing hook is logged at WARN`() {
        val hook = binding(key<Hook>(), "core", "Failing", multi = true) { Failing(intercepted) }

        val lines = logged {
            spec(core(hook, probe("core", "hooks" to key<Hooks>())), dataDir).execute {
                val hooks = services.get(key<Probe>()).values.getValue("hooks") as Hooks
                hooks.fire(intercepted, "payload")
            }
        }

        assertContains(
            lines,
            "WARN test: hook ${Failing::class.java.name} failed at 'test.intercepted': " +
                "Threw(error=java.lang.IllegalStateException: hook failed)",
        )
    }

    @Test
    fun `a hook's decision to replace the payload or stop the chain is logged at DEBUG`() {
        val decided =
            InterceptorPoint<String>(
                "test.decided",
                setOf(HookEffect.REPLACE, HookEffect.ABORT),
                FailurePolicy.FAIL_OPEN,
            )
        val replacing = object : InterceptorHook<String> {
            override val point = decided

            override suspend fun intercept(payload: String): HookDecision<String> = HookDecision.Replace("$payload!")
        }
        val stopping = object : InterceptorHook<String> {
            override val point = decided
            override val order = 1

            override suspend fun intercept(payload: String): HookDecision<String> = HookDecision.Abort("secret")
        }
        val hooks = listOf(replacing, stopping).mapIndexed { index, hook ->
            binding(key<Hook>(), "core", "Hook $index", multi = true) { hook }
        }

        val lines = logged {
            spec(core(*hooks.toTypedArray(), probe("core", "hooks" to key<Hooks>())), dataDir).execute {
                val hooks = services.get(key<Probe>()).values.getValue("hooks") as Hooks
                hooks.fire(decided, "payload")
            }
        }

        assertEquals(
            listOf(
                "DEBUG test: hook ${replacing.javaClass.name} replaced the payload at 'test.decided'",
                "DEBUG test: hook ${stopping.javaClass.name} stopped the chain at 'test.decided'",
            ),
            lines.filter { it.startsWith("DEBUG") },
        )
    }

    // Redaction.

    @Serializable
    class TokenConfig(val token: Secret)

    private class Leaky(private val token: String) : Lifecycle {
        override fun onStop() = error("GET https://user:hunter2@example.org/x?token=abc&page=2 failed, sent $token")
    }

    @Test
    fun `decoded secrets never leave the runtime in a failed start`() {
        val section = ConfigSectionSpec(key<TokenConfig>(), "", TokenConfig.serializer(), "TokenConfig")
        val config = Dependency(key<TokenConfig>(), DependencyKind.INSTANCE, "config")
        val leaky = binding(key<Service>("leaky"), "bot", "leaky", dependencies = listOf(config)) { r ->
            error("bot ${r.get(key<TokenConfig>()).token.reveal()} is unknown")
        }
        val index = TestIndex("bot", bindings = listOf(leaky), sections = listOf(section))
        val recorder = Recorder()
        lateinit var error: RuntimeStartException

        val lines = logged {
            error = spec(explicit(index), dataDir, """{"plugins": {"bot": {"token": "s3cr3t-t0ken"}}}""", recorder)
                .startFailure()
        }

        val texts = listOf(error.message.orEmpty()) + error.problems.map { it.message } +
            generateSequence(error.cause) { it.cause }.map { it.toString() } + lines + recorder.events.map { "$it" }
        assertTrue(texts.none { "s3cr3t-t0ken" in it }, "$texts")
        assertContains(error.problems.single().message, "java.lang.IllegalStateException: bot *** is unknown")
        assertEquals(DiProblemKind.CREATION_FAILED, error.problems.single().kind)
    }

    @Test
    fun `secrets and URL credentials are masked in the termination and the log`() {
        val section = ConfigSectionSpec(key<TokenConfig>(), "", TokenConfig.serializer(), "TokenConfig")
        val config = Dependency(key<TokenConfig>(), DependencyKind.INSTANCE, "config")
        val leaky = binding(key<Leaky>(), "bot", "leaky", dependencies = listOf(config)) { r ->
            Leaky(r.get(key<TokenConfig>()).token.reveal())
        }
        val index = TestIndex("bot", bindings = listOf(leaky), sections = listOf(section))
        lateinit var termination: Termination

        val lines = logged {
            termination = spec(explicit(index), dataDir, """{"plugins": {"bot": {"token": "s3cr3t-t0ken"}}}""")
                .execute()
        }

        val masked = "Stopping leaky (plugin bot) failed: java.lang.IllegalStateException: " +
            "GET https://***@example.org/x?token=***&page=2 failed, sent ***"
        assertEquals(listOf(masked), termination.problems.map { it.message })
        assertContains(lines, "WARN test: $masked")
    }
}
