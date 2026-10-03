package org.foedusprogramme.alexandrite.ksp

import org.foedusprogramme.alexandrite.sdk.di.Container
import org.foedusprogramme.alexandrite.sdk.di.DiException
import org.foedusprogramme.alexandrite.sdk.di.DiProblemKind
import org.foedusprogramme.alexandrite.sdk.di.instanceBinding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import org.foedusprogramme.alexandrite.sdk.runtime.PluginFiles
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
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
        import org.foedusprogramme.alexandrite.sdk.plugin.Plugin
        import org.foedusprogramme.alexandrite.sdk.runtime.PluginFiles
        import java.time.Clock
        import java.time.Instant
        import java.time.ZoneOffset

        @Plugin(name = "Provided")
        class ProvidedPlugin

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

    private val compiled by lazy {
        compile(sharedDir, sample, options = sampleOptions("sample-plugin")).also {
            it.assertSucceeded()
        }
    }

    private val index: PluginIndex by lazy { compiled.indexes().single() }

    @AfterAll
    fun closeSample() {
        compiled.close()
    }

    private fun build(index: PluginIndex): Container = Container.build(
        listOf(index.pluginBindings()),
        listOf(instanceBinding(key<PluginFiles>("sample-plugin"), files, "runtime", "test")),
    )

    @Test
    fun `the index binds what providers return, with their scopes, qualifiers and contributions`() {
        val probe = Probe::class.java.name
        val pluginFiles = PluginFiles::class.java.name
        val bindings = index.bindings()

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
                "ProvidedPlugin" to listOf("sample.provided.ProvidedPlugin SINGLETON <- "),
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
            bindings.groupBy({ simpleOrigin(it.origin, "sample.provided") }, ::describe),
        )
        assertEquals(setOf("sample-plugin"), bindings.mapTo(HashSet()) { it.plugin })
    }

    @Test
    fun `the container creates provided instances and closes the AutoCloseable ones`() {
        build(index).use { root ->
            assertEquals(Instant.parse("2026-10-01T12:00:00Z"), root.get(key<Clock>()).instant())
            assertEquals("Hello from sample-plugin", root.get(key<String>("greeting")))
            assertEquals("store in /data/sample-plugin at 2026-10-01T12:00:00Z", root.get(key<Probe>("store")).report())
            assertEquals(listOf("echo Hello from sample-plugin"), root.getAll(key<Probe>()).map { it.report() })
            val error = assertFailsWith<DiException> { root.get(key<Probe>("connection")) }
            assertEquals(DiProblemKind.SCOPE, error.problems.single().kind)

            val channel = root.child("tg", setOf("sample-plugin"))
            val connection = channel.get(key<Probe>("connection"))
            assertEquals("open since 2026-10-01T12:00:00Z", connection.report())
            channel.close()
            assertEquals("closed since 2026-10-01T12:00:00Z", connection.report())
        }
    }

    @Test
    fun `a plugin-local dependency is qualified with the plugin id`() {
        val store = source(
            "Store.kt",
            """
            package sample

            import org.foedusprogramme.alexandrite.sdk.di.Singleton
            import org.foedusprogramme.alexandrite.sdk.runtime.PluginFiles

            @Singleton
            class Store(val files: PluginFiles, val later: Lazy<PluginFiles>)
            """,
        )
        compile(
            workingDir.resolve("local"),
            store,
            entry("sample"),
            options = sampleOptions("weather"),
        ).use { compiled ->
            compiled.assertSucceeded()

            val qualified = key<PluginFiles>("weather")
            val binding = compiled.indexes().single().bindings().single { it.origin == "sample.Store" }
            assertEquals(listOf(qualified, qualified), binding.dependencies.map { it.key })
        }
    }

    private companion object {
        @TempDir
        lateinit var sharedDir: File
    }
}
