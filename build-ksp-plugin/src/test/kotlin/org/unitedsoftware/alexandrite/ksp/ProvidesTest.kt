package org.unitedsoftware.alexandrite.ksp

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.chat.ChatStates
import org.unitedsoftware.alexandrite.sdk.di.container.Container
import org.unitedsoftware.alexandrite.sdk.di.container.DiException
import org.unitedsoftware.alexandrite.sdk.di.container.DiProblemKind
import org.unitedsoftware.alexandrite.sdk.di.container.instanceBinding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.plugin.PluginFiles
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo
import org.unitedsoftware.alexandrite.sdk.plugin.PluginScope
import org.unitedsoftware.alexandrite.sdk.runtime.RuntimeControl
import java.io.File
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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

        import org.unitedsoftware.alexandrite.ksp.Probe
        import org.unitedsoftware.alexandrite.sdk.di.Binds
        import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
        import org.unitedsoftware.alexandrite.sdk.di.Contribute
        import org.unitedsoftware.alexandrite.sdk.di.Named
        import org.unitedsoftware.alexandrite.sdk.di.Provides
        import org.unitedsoftware.alexandrite.sdk.di.Singleton
        import org.unitedsoftware.alexandrite.sdk.plugin.Plugin
        import org.unitedsoftware.alexandrite.sdk.plugin.PluginFiles
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
        override val cacheDir: Path = Path.of("/cache/sample-plugin")
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
                "Connections.connection(java.time.Clock)" to listOf(
                    "@Named(\"connection\") sample.provided.Connection CHANNEL_INSTANCE <- " +
                        "clock: INSTANCE java.time.Clock",
                    "@Named(\"connection\") $probe CHANNEL_INSTANCE unmanaged <- " +
                        "@Binds: INSTANCE @Named(\"connection\") sample.provided.Connection",
                ),
                "Greetings.greeting($pluginFiles)" to listOf(
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
                "echo(kotlin.String)" to listOf(
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

            import org.unitedsoftware.alexandrite.sdk.chat.ChatStates
            import org.unitedsoftware.alexandrite.sdk.di.Singleton
            import org.unitedsoftware.alexandrite.sdk.plugin.PluginFiles
            import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo
            import org.unitedsoftware.alexandrite.sdk.plugin.PluginScope
            import org.unitedsoftware.alexandrite.sdk.runtime.RuntimeControl

            @Singleton
            class Store(
                val files: PluginFiles,
                val later: Lazy<PluginFiles>,
                val info: PluginInfo,
                val scope: PluginScope,
                val control: RuntimeControl,
                val states: ChatStates,
            )
            """,
        )
        compile(
            workingDir.resolve("local"),
            store,
            entry("sample"),
            options = sampleOptions("weather"),
        ).use { compiled ->
            compiled.assertSucceeded()

            val files = key<PluginFiles>("weather")
            val binding = compiled.indexes().single().bindings().single { it.origin == "sample.Store" }
            val expected = listOf(
                files,
                files,
                key<PluginInfo>("weather"),
                key<PluginScope>("weather"),
                key<RuntimeControl>("weather"),
                key<ChatStates>("weather"),
            )
            assertEquals(expected, binding.dependencies.map { it.key })
        }
    }

    @Test
    fun `a component that contributes its own type is bound only as that contribution`() {
        val profiles = source(
            "Profiles.kt",
            """
            package sample

            import org.unitedsoftware.alexandrite.sdk.di.Contribute
            import org.unitedsoftware.alexandrite.sdk.di.Provides
            import org.unitedsoftware.alexandrite.sdk.di.Singleton

            class Profile(val name: String)

            @Provides
            @Contribute(Profile::class)
            fun reviewer(): Profile = Profile("reviewer")

            @Provides
            @Contribute(Profile::class)
            fun writer(): Profile = Profile("writer")

            @Singleton
            @Contribute(Marker::class)
            class Marker
            """,
        )
        compile(workingDir.resolve("itself"), profiles, entry("sample")).use { compiled ->
            compiled.assertSucceeded()

            val index = compiled.indexes().single()
            assertEquals(
                mapOf(
                    "Marker" to listOf("sample.Marker SINGLETON multi <- "),
                    "reviewer()" to listOf("sample.Profile SINGLETON multi <- "),
                    "writer()" to listOf("sample.Profile SINGLETON multi <- "),
                ),
                index.bindings().filter { it.origin != "sample.SamplePlugin" }
                    .groupBy({ simpleOrigin(it.origin, "sample") }, ::describe),
            )
            Container.build(listOf(index.pluginBindings()), emptyList()).close()
        }
    }

    @Test
    fun `a provider in a companion object is called through it, and overloads have their own origins`() {
        val clocks = source(
            "Clocks.kt",
            """
            package sample

            import org.unitedsoftware.alexandrite.sdk.di.Named
            import org.unitedsoftware.alexandrite.sdk.di.Provides
            import java.time.Clock
            import java.time.ZoneId

            class Clocks {
                companion object {
                    @Provides
                    @Named("utc")
                    fun clock(): Clock = Clock.systemUTC()

                    @Provides
                    @Named("zoned")
                    fun clock(@Named("zone") zone: String): Clock = Clock.system(ZoneId.of(zone))
                }
            }
            """,
        )
        compile(workingDir.resolve("companion"), clocks, entry("sample")).use { compiled ->
            compiled.assertSucceeded()

            val index = compiled.indexes().single()
            assertEquals(
                listOf("sample.Clocks.Companion.clock()", "sample.Clocks.Companion.clock(kotlin.String)"),
                index.bindings().map { it.origin }.filter { it.startsWith("sample.Clocks") },
            )
            val zone = instanceBinding(key<String>("zone"), "Asia/Shanghai", "test", "test")
            Container.build(listOf(index.pluginBindings()), listOf(zone)).use { container ->
                assertEquals(ZoneOffset.UTC, container.get(key<Clock>("utc")).zone)
                assertEquals(ZoneId.of("Asia/Shanghai"), container.get(key<Clock>("zoned")).zone)
            }
        }
    }

    private companion object {
        @TempDir
        lateinit var sharedDir: File
    }
}
