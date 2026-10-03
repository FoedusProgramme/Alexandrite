package org.foedusprogramme.alexandrite.ksp

import org.foedusprogramme.alexandrite.sdk.di.Container
import org.foedusprogramme.alexandrite.sdk.di.DiException
import org.foedusprogramme.alexandrite.sdk.di.ModuleIndex
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind
import org.foedusprogramme.alexandrite.sdk.di.instanceBinding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.runtime.PluginFiles
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProvidesTest {
    @TempDir
    lateinit var workingDir: File

    private val sample = source(
        "Provided.kt",
        """
        package sample.provided

        import org.foedusprogramme.alexandrite.ksp.Probe
        import org.foedusprogramme.alexandrite.sdk.di.Binds
        import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
        import org.foedusprogramme.alexandrite.sdk.di.Contribute
        import org.foedusprogramme.alexandrite.sdk.di.Named
        import org.foedusprogramme.alexandrite.sdk.di.Provides
        import org.foedusprogramme.alexandrite.sdk.di.Singleton
        import org.foedusprogramme.alexandrite.sdk.runtime.PluginFiles
        import java.time.Clock
        import java.time.Instant
        import java.time.ZoneOffset

        @Provides
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC)

        object Greetings {
            @Provides
            @Singleton
            @Named("greeting")
            fun greeting(files: PluginFiles): String = "Hello from " + files.dataDir.fileName
        }

        @Singleton
        @Named("store")
        @Binds(Probe::class)
        class Store(private val files: PluginFiles?, private val clock: () -> Clock) : Probe {
            override fun report(): String = "store in " + files?.dataDir + " at " + clock().instant()
        }

        class Connection(private val clock: Clock) : Probe, AutoCloseable {
            private var open = true

            override fun report(): String = (if (open) "open" else "closed") + " since " + clock.instant()

            override fun close() {
                open = false
            }
        }

        object Connections {
            @Provides
            @ChannelInstanceScoped
            @Named("connection")
            @Binds(Probe::class)
            fun connection(clock: Clock): Connection = Connection(clock)
        }

        class Echo(private val word: String) : Probe {
            override fun report(): String = "echo " + word
        }

        @Provides
        @Contribute(Probe::class)
        fun echo(@Named("greeting") greeting: String): Echo = Echo(greeting)
        """,
    )

    private val files = object : PluginFiles {
        override val dataDir: Path = Path.of("/data/sample-plugin")
    }

    private fun compileSample(): Compiled =
        compile(workingDir, sample, options = mapOf(MODULE_OPTION to "sample-plugin")).also { it.assertSucceeded() }

    private fun build(index: ModuleIndex): Container = Container.build(
        listOf(index),
        listOf(instanceBinding(key<PluginFiles>("sample-plugin"), files, "runtime", "test")),
    )

    @Test
    fun `the index binds what providers return, with their scopes, qualifiers and contributions`() {
        val probe = Probe::class.java.name
        val pluginFiles = PluginFiles::class.java.name
        val bindings = compileSample().indexes().single().bindings()

        assertEquals(
            mapOf(
                "Connections.connection()" to listOf(
                    "@Named(\"connection\") sample.provided.Connection CHANNEL_INSTANCE <- " +
                        "clock: INSTANCE java.time.Clock",
                    "@Named(\"connection\") $probe CHANNEL_INSTANCE unmanaged <- " +
                        "@Binds: INSTANCE @Named(\"connection\") sample.provided.Connection",
                ),
                "Greetings.greeting()" to listOf(
                    "@Named(\"greeting\") kotlin.String SINGLETON <- " +
                        "files: INSTANCE @Named(\"sample-plugin\") $pluginFiles",
                ),
                "Store" to listOf(
                    "@Named(\"store\") sample.provided.Store SINGLETON <- " +
                        "files: OPTIONAL @Named(\"sample-plugin\") $pluginFiles, clock: PROVIDER java.time.Clock",
                    "@Named(\"store\") $probe SINGLETON unmanaged <- " +
                        "@Binds: INSTANCE @Named(\"store\") sample.provided.Store",
                ),
                "clock()" to listOf("java.time.Clock SINGLETON <- "),
                "echo()" to listOf(
                    "sample.provided.Echo SINGLETON <- greeting: INSTANCE @Named(\"greeting\") kotlin.String",
                    "$probe SINGLETON multi unmanaged <- @Contribute: INSTANCE sample.provided.Echo",
                ),
            ),
            bindings.groupBy({ simpleOrigin(it.origin) }, ::describe),
        )
        assertEquals(setOf("sample-plugin"), bindings.mapTo(HashSet()) { it.module })
    }

    @Test
    fun `the generated binding of a provider calls the function`() {
        val generated = compileSample().generated("sample.provided.SamplePluginIndex")

        assertContains(generated, "    sample.provided.Greetings.greeting(\n")
        assertContains(
            generated,
            "        r.get(key<org.foedusprogramme.alexandrite.sdk.runtime.PluginFiles>(\"sample-plugin\")),",
        )
        assertContains(generated, ") { sample.provided.clock() }")
        assertContains(generated, "    module = \"sample-plugin\",\n")
    }

    @Test
    fun `the container creates provided instances and closes the AutoCloseable ones`() {
        build(compileSample().indexes().single()).use { root ->
            assertEquals(Instant.parse("2026-10-01T12:00:00Z"), root.get(key<Clock>()).instant())
            assertEquals("Hello from sample-plugin", root.get(key<String>("greeting")))
            assertEquals("store in /data/sample-plugin at 2026-10-01T12:00:00Z", root.get(key<Probe>("store")).report())
            assertEquals(listOf("echo Hello from sample-plugin"), root.getAll(key<Probe>()).map { it.report() })
            val error = assertFailsWith<DiException> { root.get(key<Probe>("connection")) }
            assertEquals(ProblemKind.SCOPE, error.problems.single().kind)

            val channel = root.child("tg", setOf("sample-plugin"))
            val connection = channel.get(key<Probe>("connection"))
            assertEquals("open since 2026-10-01T12:00:00Z", connection.report())
            channel.close()
            assertEquals("closed since 2026-10-01T12:00:00Z", connection.report())
        }
    }

    @Test
    fun `a module-local dependency is qualified with the module's name`() {
        val compiled = compile(
            workingDir,
            source(
                "Store.kt",
                """
                package sample

                import org.foedusprogramme.alexandrite.sdk.di.Singleton
                import org.foedusprogramme.alexandrite.sdk.runtime.PluginFiles

                @Singleton
                class Store(val files: PluginFiles, val later: Lazy<PluginFiles>)
                """,
            ),
            options = mapOf(MODULE_OPTION to "weather"),
        )
        compiled.assertSucceeded()

        val qualified = key<PluginFiles>("weather")
        assertEquals(
            listOf(qualified, qualified),
            compiled.indexes().single().bindings().single().dependencies.map {
                it.key
            },
        )
    }

    private fun simpleOrigin(origin: String): String =
        origin.removeSuffix(" (module sample-plugin)").removePrefix("sample.provided.")
}
