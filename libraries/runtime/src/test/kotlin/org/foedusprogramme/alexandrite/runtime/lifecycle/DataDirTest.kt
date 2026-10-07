package org.foedusprogramme.alexandrite.runtime.lifecycle

import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.Probe
import org.foedusprogramme.alexandrite.runtime.RuntimeConfig
import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.runtime.RuntimeSpec
import org.foedusprogramme.alexandrite.runtime.RuntimeStartException
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.runtime.TestIndex
import org.foedusprogramme.alexandrite.runtime.execute
import org.foedusprogramme.alexandrite.runtime.explicit
import org.foedusprogramme.alexandrite.runtime.probe
import org.foedusprogramme.alexandrite.runtime.spec
import org.foedusprogramme.alexandrite.runtime.startFailure
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.plugin.PluginFiles
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class DataDirTest {
    @TempDir
    lateinit var dataDir: Path

    private fun core() = explicit(TestIndex("core"))

    private fun files(id: String) = probe("core", "files" to key<PluginFiles>(id))

    private fun AlexandriteRuntime.files(): PluginFiles =
        services.get(key<Probe>()).values.getValue("files") as PluginFiles

    private fun permissions(path: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    private fun assumePosix() =
        assumeTrue("posix" in dataDir.fileSystem.supportedFileAttributeViews(), "no POSIX permissions")

    @Test
    fun `the runtime creates its data directory and writes its pid into the lock file it keeps`() {
        val nested = dataDir.resolve("a/b")
        val lockFile = nested.resolve("runtime.lock")

        lateinit var pid: String

        spec(core(), nested).execute { pid = Files.readString(lockFile).trim() }

        assertEquals("${ProcessHandle.current().pid()}", pid)
        assertTrue(Files.exists(lockFile))
    }

    @Test
    fun `a second runtime of this JVM cannot use a data directory in use`() {
        lateinit var error: RuntimeStartException

        spec(core(), dataDir).execute {
            error =
                assertFailsWith<RuntimeStartException> {
                    AlexandriteRuntime.run(spec(core(), dataDir)) { fail("started") }
                }
        }

        assertEquals(StartStage.DATA_DIR, error.stage)
        assertEquals(listOf(RuntimeProblemKind.DATA_DIR_LOCKED), error.problems.map { it.kind })
        assertEquals(
            "Data directory '$dataDir' is in use: another runtime of this JVM holds its lock file runtime.lock. " +
                "Stop that runtime or give this one another data directory.",
            error.problems.single().message,
        )
        spec(core(), dataDir).execute()
    }

    @Test
    fun `a data directory another process holds fails the DATA_DIR stage with its pid`() {
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        assumeTrue(Files.isExecutable(java), "no java executable at $java")
        val command = listOf(
            java.toString(),
            "-cp",
            System.getProperty("java.class.path"),
            "org.foedusprogramme.alexandrite.runtime.lifecycle.LockHolderKt",
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

            val error = spec(core(), dataDir).startFailure()

            assertEquals(StartStage.DATA_DIR, error.stage)
            assertContains(error.message!!, "is in use: process ${holder.pid()} holds its lock file runtime.lock.")
        } finally {
            holder.outputStream.close()
            if (!holder.waitFor(10, TimeUnit.SECONDS)) holder.destroyForcibly()
        }
    }

    @Test
    fun `the runtime creates the data and cache roots owner-only`() {
        assumePosix()
        val data = dataDir.resolve("a/data")
        val cache = dataDir.resolve("b/cache")
        val config = RuntimeConfig.builder(data).cacheDir(cache).name("test").build()

        RuntimeSpec.builder(config, core()).build().execute()

        assertEquals(
            List(4) { "rwx------" },
            listOf(data, data.parent, cache, cache.parent).map(::permissions),
        )
    }

    @Test
    fun `the cache root defaults to a directory in the data directory`() {
        spec(core(), dataDir).execute()

        assertTrue(Files.isDirectory(dataDir.resolve("cache")))
    }

    @Test
    fun `a plugin's data and cache directories are created owner-only when first asked for`() {
        assumePosix()
        spec(explicit(TestIndex("core", bindings = listOf(files("core")))), dataDir).execute {
            assertFalse(Files.exists(dataDir.resolve("plugins")))
            assertFalse(Files.exists(dataDir.resolve("cache/plugins")))
            val files = files()

            assertEquals(dataDir.resolve("plugins/core"), files.dataDir)
            assertEquals(dataDir.resolve("cache/plugins/core"), files.cacheDir)
            assertEquals(
                List(4) { "rwx------" },
                listOf("plugins", "plugins/core", "cache/plugins", "cache/plugins/core")
                    .map { permissions(dataDir.resolve(it)) },
            )
        }
    }

    @Test
    fun `directories that exist keep their permissions`() {
        assumePosix()
        val open = PosixFilePermissions.fromString("rwxr-xr-x")
        val data = Files.createDirectories(dataDir.resolve("data"))
        val directories = listOf("", "cache", "plugins/core", "cache/plugins/core").map(data::resolve)
        directories.forEach { Files.setPosixFilePermissions(Files.createDirectories(it), open) }
        spec(explicit(TestIndex("core", bindings = listOf(files("core")))), data).execute {
            listOf(files().dataDir, files().cacheDir)
        }

        assertEquals(List(4) { "rwxr-xr-x" }, directories.map(::permissions))
    }

    @Test
    fun `a cache root that cannot be created fails the DATA_DIR stage and releases the data directory`() {
        val file = Files.writeString(dataDir.resolve("file"), "")
        val data = dataDir.resolve("data")
        val config = RuntimeConfig.builder(data).cacheDir(file).name("test").build()

        val error = RuntimeSpec.builder(config, core()).build().startFailure()

        assertEquals(StartStage.DATA_DIR, error.stage)
        assertIs<FileAlreadyExistsException>(error.cause)
        spec(core(), data).execute()
    }

    @Test
    fun `a data directory that cannot be created fails the DATA_DIR stage`() {
        val file = Files.writeString(dataDir.resolve("file"), "")

        val error = spec(core(), file).startFailure()

        assertEquals(StartStage.DATA_DIR, error.stage)
        assertIs<FileAlreadyExistsException>(error.cause)
    }
}
