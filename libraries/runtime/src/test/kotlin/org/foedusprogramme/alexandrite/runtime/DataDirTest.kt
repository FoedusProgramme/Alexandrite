package org.foedusprogramme.alexandrite.runtime

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DataDirTest {
    @TempDir
    lateinit var dataDir: Path

    private fun core() = explicit(TestIndex("core"))

    @Test
    fun `the runtime creates its data directory and writes its pid into the lock file it keeps`() {
        val nested = dataDir.resolve("a/b")
        val lockFile = nested.resolve("runtime.lock")

        val pid = runtime(core(), nested).started().use { Files.readString(lockFile).trim() }

        assertEquals("${ProcessHandle.current().pid()}", pid)
        assertTrue(Files.exists(lockFile))
    }

    @Test
    fun `a second runtime of this JVM cannot use a data directory in use`() {
        runtime(core(), dataDir).started().use {
            val error = runtime(core(), dataDir).startFailure()

            assertEquals(StartStage.DATA_DIR, error.stage)
            assertEquals(listOf(RuntimeProblemKind.DATA_DIR_LOCKED), error.problems.map { it.kind })
            assertEquals(
                "Data directory '$dataDir' is in use: another runtime of this JVM holds its lock file runtime.lock. " +
                    "Stop that runtime or give this one another data directory.",
                error.problems.single().message,
            )
        }

        runtime(core(), dataDir).started().close()
    }

    @Test
    fun `a data directory another process holds fails the DATA_DIR stage with its pid`() {
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        assumeTrue(Files.isExecutable(java), "no java executable at $java")
        val command = listOf(
            java.toString(),
            "-cp",
            System.getProperty("java.class.path"),
            "org.foedusprogramme.alexandrite.runtime.LockHolderKt",
            dataDir.toString(),
        )
        val holder = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: IOException) {
            assumeTrue(false, "cannot start a JVM: $e")
            return
        }
        try {
            assertEquals("locked", holder.inputReader().readLine())

            val error = runtime(core(), dataDir).startFailure()

            assertEquals(StartStage.DATA_DIR, error.stage)
            assertContains(error.message!!, "is in use: process ${holder.pid()} holds its lock file runtime.lock.")
        } finally {
            holder.outputStream.close()
            if (!holder.waitFor(10, TimeUnit.SECONDS)) holder.destroyForcibly()
        }
    }

    @Test
    fun `a data directory that cannot be created fails the DATA_DIR stage`() {
        val file = Files.writeString(dataDir.resolve("file"), "")

        val error = runtime(core(), file).startFailure()

        assertEquals(StartStage.DATA_DIR, error.stage)
        assertIs<FileAlreadyExistsException>(error.cause)
    }
}
