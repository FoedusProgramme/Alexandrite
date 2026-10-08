package org.foedusprogramme.alexandrite.app

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.runtime.RuntimeSpec
import org.foedusprogramme.alexandrite.runtime.RuntimeStartException
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.runtime.Termination
import org.foedusprogramme.alexandrite.sdk.channel.Channel
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.container.Dependency
import org.foedusprogramme.alexandrite.sdk.di.container.DependencyKind
import org.foedusprogramme.alexandrite.sdk.di.container.binding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.runtime.RuntimeControl
import org.foedusprogramme.alexandrite.sdk.runtime.StopKind
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import org.junit.jupiter.api.io.TempDir
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
        val spec = spec(dataDir, TestIndex("a", listOf(failing("a", "stop"))))

        val termination = runBlocking { AlexandriteRuntime.run(spec) {} }

        assertEquals(1, termination.problems.size)
        assertEquals(0, exitCode(termination.request.kind))
    }
}
