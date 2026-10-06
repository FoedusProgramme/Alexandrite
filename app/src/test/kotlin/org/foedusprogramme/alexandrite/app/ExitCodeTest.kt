package org.foedusprogramme.alexandrite.app

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.RuntimeSpec
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.runtime.Termination
import org.foedusprogramme.alexandrite.sdk.di.container.Dependency
import org.foedusprogramme.alexandrite.sdk.di.container.DependencyKind
import org.foedusprogramme.alexandrite.sdk.di.container.binding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.runtime.StopKind
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

class ExitCodeTest {
    @TempDir
    lateinit var dataDir: Path

    private fun stopped(kind: StopKind): Termination =
        runBlocking { AlexandriteRuntime.run(spec(dataDir)) { requestStop(StopRequest(kind, "test")) } }

    private fun failed(spec: RuntimeSpec): Termination =
        runBlocking { AlexandriteRuntime.run(spec) { fail("the block ran") } }

    private fun failedAt(stage: StartStage): Termination {
        val missing = Dependency(key<String>("missing"), DependencyKind.INSTANCE, "missing")
        val termination = when (stage) {
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
        assertEquals(stage, assertIs<Termination.Cause.StartFailed>(termination.cause).error.stage)
        return termination
    }

    @Test
    fun `a requested stop exits by its kind`() {
        assertEquals(
            mapOf(StopKind.SHUTDOWN to 0, StopKind.RESTART to 75, StopKind.FAILURE to 1),
            StopKind.entries.associateWith { exitCode(stopped(it)) },
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
    fun `problems met while stopping do not change the code`() {
        val spec = spec(dataDir, TestIndex("a", listOf(failing("a", "stop"))))

        val termination = runBlocking { AlexandriteRuntime.run(spec) {} }

        assertEquals(1, termination.problems.size)
        assertEquals(0, exitCode(termination))
    }
}
