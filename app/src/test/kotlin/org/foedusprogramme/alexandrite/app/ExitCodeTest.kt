package org.foedusprogramme.alexandrite.app

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.RuntimeStartException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ExitCodeTest {
    @TempDir
    lateinit var dataDir: Path

    private fun stopped(kind: StopKind): Termination = runBlocking {
        val runtime = runtime(dataDir)
        runtime.start()
        runtime.stop(StopRequest(kind, "test"))
    }

    private fun failed(runtime: AlexandriteRuntime): Termination = runBlocking {
        assertFailsWith<RuntimeStartException> { runtime.start() }
        runtime.awaitTermination()
    }

    private fun failedAt(stage: StartStage): Termination {
        val missing = Dependency(key<String>("missing"), DependencyKind.INSTANCE, "missing")
        val termination = when (stage) {
            StartStage.DATA_DIR -> failed(runtime(Files.writeString(dataDir.resolve("file"), "")))

            StartStage.PLUGINS -> failed(runtime(dataDir, TestIndex("twin"), TestIndex("twin")))

            StartStage.CONFIG -> failed(runtime(dataDir, config = """{"bogus": {}}"""))

            StartStage.GRAPH ->
                failed(
                    runtime(
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

            StartStage.START -> failed(runtime(dataDir, TestIndex("a", listOf(failing("a", "start")))))

            StartStage.OPEN -> failed(runtime(dataDir, TestIndex("a", listOf(failing("a", "open")))))
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
        val termination = runBlocking {
            val runtime = runtime(dataDir, TestIndex("a", listOf(failing("a", "stop"))))
            runtime.start()
            runtime.stop(StopRequest(StopKind.SHUTDOWN, "test"))
        }

        assertEquals(1, termination.problems.size)
        assertEquals(0, exitCode(termination))
    }
}
