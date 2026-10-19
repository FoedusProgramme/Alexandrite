package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.runtime.AlexandriteRuntime
import org.unitedsoftware.alexandrite.runtime.RuntimeProblemKind
import org.unitedsoftware.alexandrite.runtime.RuntimeSpec
import org.unitedsoftware.alexandrite.runtime.RuntimeStartException
import org.unitedsoftware.alexandrite.runtime.StartStage
import org.unitedsoftware.alexandrite.runtime.Termination
import org.unitedsoftware.alexandrite.sdk.channel.Channel
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.container.Dependency
import org.unitedsoftware.alexandrite.sdk.di.container.DependencyKind
import org.unitedsoftware.alexandrite.sdk.di.container.binding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.runtime.RuntimeControl
import org.unitedsoftware.alexandrite.sdk.runtime.StopKind
import org.unitedsoftware.alexandrite.sdk.runtime.StopRequest
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

class ExitCodeTest {
    @TempDir
    lateinit var dataDir: Path

    private fun stopped(kind: StopKind): Termination =
        runBlocking { AlexandriteRuntime.run(spec(dataDir)) { stop(StopRequest(kind, "test")) } }

    private fun failed(spec: RuntimeSpec): RuntimeStartException = assertFailsWith<RuntimeStartException> {
        runBlocking { AlexandriteRuntime.run(spec) { fail("the block ran") } }
    }

    private fun failedAt(stage: StartStage): RuntimeStartException {
        val missing = Dependency(key<String>("missing"), DependencyKind.INSTANCE, "missing")
        val error = when (stage) {
            StartStage.DATA_DIR -> failed(spec(Files.writeString(dataDir.resolve("file"), "")))

            StartStage.PLUGINS -> failed(spec(dataDir, TestIndex("twin"), TestIndex("twin")))

            StartStage.CONFIG -> failed(spec(dataDir, config = """{"bogus": {}}"""))

            StartStage.GRAPH ->
                failed(
                    spec(
                        dataDir,
                        TestIndex(
                            "a",
                            listOf(
                                binding(key<Int>(), "a", "Int", dependencies = listOf(missing)) {
                                    1
                                },
                            ),
                        ),
                    ),
                )

            StartStage.START -> failed(spec(dataDir, TestIndex("a", listOf(failing("a", "start")))))

            StartStage.OPEN -> failed(spec(dataDir, TestIndex("a", listOf(failing("a", "open")))))
        }
        assertEquals(stage, error.stage)
        return error
    }

    private class Stopping(private val control: RuntimeControl, private val kind: StopKind) : Lifecycle {
        override suspend fun onStart() = control.stop(StopRequest(kind, "test"))
    }

    private fun stoppedWhileStarting(kind: StopKind): RuntimeStartException {
        val control = key<RuntimeControl>("a")
        val stopping = binding(
            key<Stopping>(),
            "a",
            "Stopping",
            dependencies = listOf(Dependency(control, DependencyKind.INSTANCE, "control")),
        ) { r -> Stopping(r.get(control), kind) }
        return failed(spec(dataDir, TestIndex("a", listOf(stopping))))
    }

    @Test
    fun `a requested stop exits by its kind`() {
        assertEquals(
            mapOf(StopKind.SHUTDOWN to 0, StopKind.RESTART to 75, StopKind.FAILURE to 1),
            StopKind.entries.associateWith { exitCode(stopped(it).request.kind) },
        )
    }

    @Test
    fun `a failed start exits 78 when restarting cannot help and 1 otherwise`() {
        assertEquals(
            mapOf(
                StartStage.DATA_DIR to 1,
                StartStage.PLUGINS to 78,
                StartStage.CONFIG to 78,
                StartStage.GRAPH to 78,
                StartStage.START to 1,
                StartStage.OPEN to 1,
            ),
            StartStage.entries.associateWith { exitCode(failedAt(it)) },
        )
    }

    @Test
    fun `a start that failed on the channel rules exits 78`() {
        val channel = binding<Channel>(key(), "a", "Bot", multi = true, name = "bot") { error("never created") }

        val error = failed(spec(dataDir, TestIndex("a", listOf(channel))))

        assertEquals(StartStage.CONFIG, error.stage)
        assertEquals(listOf(RuntimeProblemKind.CHANNEL_CONTRIBUTIONS), error.problems.map { it.kind })
        assertEquals(78, exitCode(error))
    }

    @Test
    fun `a GRAPH stage that failed creating an instance exits 1`() {
        val broken = binding(key<Int>(), "a", "Int") { throw NoClassDefFoundError("org/example/Missing") }

        val error = failed(spec(dataDir, TestIndex("a", listOf(broken))))

        assertEquals(StartStage.GRAPH, error.stage)
        assertEquals(1, exitCode(error))
    }

    @Test
    fun `a config problem that a component finds when created or started exits 78`() {
        val problem = ConfigException("plugins.a.endpoint", "no endpoint 'local'")
        val created = binding(key<Int>(), "a", "Int") { throw problem }
        val started = binding(key<Lifecycle>(), "a", "Starting") {
            object : Lifecycle {
                override suspend fun onStart() = throw problem
            }
        }

        val errors = listOf(created, started).map { failed(spec(dataDir, TestIndex("a", listOf(it)))) }

        assertEquals(listOf(StartStage.GRAPH, StartStage.START), errors.map { it.stage })
        assertEquals(
            List(2) {
                listOf(RuntimeProblemKind.INVALID_CONFIG)
            },
            errors.map { e -> e.problems.map { it.kind } },
        )
        assertEquals(listOf(78, 78), errors.map(::exitCode))
    }

    @Test
    fun `a stop requested while starting exits by its kind`() {
        val errors = StopKind.entries.associateWith { stoppedWhileStarting(it) }

        assertEquals(List(3) { StartStage.START }, errors.values.map { it.stage })
        assertEquals(
            mapOf(StopKind.SHUTDOWN to 0, StopKind.RESTART to 75, StopKind.FAILURE to 1),
            errors.mapValues { exitCode(it.value) },
        )
    }

    @Test
    fun `problems met while stopping do not change the code`() {
        val json = """{"app": {"plugins": ["faulty"]}, "plugins": {"faulty": {}}}"""
        val config = Files.writeString(dataDir.resolve("alexandrite.json"), json)
        val err = ByteArrayOutputStream()
        var termination: Termination? = null

        val code = run(
            listOf("--config", "$config", "--data-dir", "${dataDir.resolve("data")}"),
            emptyMap(),
            osName = "Linux",
            home = dataDir.resolve("home"),
            err = PrintStream(err, true),
            execute = { spec, _ -> AlexandriteRuntime.run(spec) {}.also { termination = it } },
        )

        assertEquals(1, termination?.problems?.size)
        assertEquals(0, code, err.toString())
    }
}
