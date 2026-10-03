package org.foedusprogramme.alexandrite.ksp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.foedusprogramme.alexandrite.sdk.config.JsonConfigSource
import org.foedusprogramme.alexandrite.sdk.config.configBindings
import org.foedusprogramme.alexandrite.sdk.di.Binding
import org.foedusprogramme.alexandrite.sdk.di.Container
import org.foedusprogramme.alexandrite.sdk.di.DiException
import org.foedusprogramme.alexandrite.sdk.di.ModuleIndex
import org.foedusprogramme.alexandrite.sdk.di.instanceBinding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class IndexTest {
    @TempDir
    lateinit var workingDir: File

    private val sampleConfig = source(
        "Config.kt",
        """
        package sample.plugin.config

        import kotlinx.serialization.Serializable
        import org.foedusprogramme.alexandrite.sdk.config.ConfigSection

        @ConfigSection
        @Serializable
        data class SampleConfig(val loud: Boolean = false)

        @ConfigSection("greeting")
        @Serializable
        data class GreetingConfig(val word: String = "Hello")
        """,
    )

    private val sample = source(
        "Sample.kt",
        """
        package sample.plugin

        import org.foedusprogramme.alexandrite.ksp.Probe
        import org.foedusprogramme.alexandrite.sdk.di.Binds
        import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
        import org.foedusprogramme.alexandrite.sdk.di.Contribute
        import org.foedusprogramme.alexandrite.sdk.di.Inject
        import org.foedusprogramme.alexandrite.sdk.di.Named
        import org.foedusprogramme.alexandrite.sdk.di.Singleton
        import sample.plugin.config.GreetingConfig
        import sample.plugin.config.SampleConfig

        interface Clock {
            fun now(): String
        }

        @Binds(Clock::class)
        class FixedClock : Clock {
            override fun now(): String = "noon"
        }

        interface Greeter {
            fun greet(name: String): String
        }

        @Singleton
        @Named("polite")
        @Binds(Greeter::class)
        class PoliteGreeter(
            private val config: GreetingConfig,
            private val sample: SampleConfig,
            private val clock: () -> Clock,
        ) : Greeter {
            override fun greet(name: String): String =
                config.word + " " + name + ", it is " + clock().now() + if (sample.loud) "!" else ""
        }

        @Contribute(Probe::class)
        class EchoTool : Probe {
            override fun report(): String = "echo"
        }

        @Contribute(Probe::class)
        class TimeTool(private val clock: Lazy<Clock>) : Probe {
            override fun report(): String = "time " + clock.value.now()
        }

        interface Audit

        @Binds(Map::class)
        class Limits : Map<String, Int> by mapOf("rounds" to 3)

        @Singleton
        @Named("app")
        @Binds(Probe::class)
        class App @Inject constructor(
            @Named("polite") private val greeter: Greeter,
            @Named("user") private val user: String,
            private val tools: List<Probe>,
            private val audit: Audit?,
            private val limits: Map<String, Int>,
        ) : Probe {
            constructor(greeter: Greeter) : this(greeter, "nobody", emptyList(), null, emptyMap())

            override fun report(): String = greeter.greet(user) + "; tools=" + tools.map { it.report() } +
                "; audit=" + audit + "; rounds=" + limits["rounds"]
        }

        @ChannelInstanceScoped
        @Named("session")
        @Binds(Probe::class)
        class Session(@Named("app") private val app: Probe) : Probe {
            override fun report(): String = "session of " + app.report()
        }
        """,
    )

    private val config = JsonConfigSource(
        Json.parseToJsonElement(
            """{"plugins": {"sample-plugin": {"enabled": true, "loud": true, "greeting": {"word": "Hi"}}}}""",
        ).jsonObject,
    )

    private fun compileSample(): Compiled =
        compile(workingDir, sampleConfig, sample, options = mapOf(MODULE_OPTION to "sample-plugin")).also {
            it.assertSucceeded()
        }

    private fun sampleIndex(): ModuleIndex = compileSample().indexes().single()

    private fun overrides(index: ModuleIndex): List<Binding<*>> =
        configBindings(index, config) + instanceBinding(key<String>("user"), "Ada", "test", "test")

    // End to end.

    @Test
    fun `ServiceLoader finds the generated index in the plugin's package`() {
        val compiled = compileSample()
        val index = compiled.indexes().single()

        assertEquals("sample.plugin.SamplePluginIndex", index.javaClass.name)
        assertEquals("sample.plugin.SamplePluginIndex\n", compiled.service())
        assertEquals("sample-plugin", index.module)
        assertEquals("plugins.sample-plugin", index.configRoot)
    }

    @Test
    fun `the container resolves the generated graph with the decoded config sections`() {
        val index = sampleIndex()

        Container.build(listOf(index), overrides(index)).use { container ->
            assertEquals(
                "Hi Ada, it is noon!; tools=[echo, time noon]; audit=null; rounds=3",
                container.get(key<Probe>("app")).report(),
            )
            assertEquals(listOf("echo", "time noon"), container.getAll(key<Probe>()).map { it.report() })
        }
    }

    @Test
    fun `a channel instance container creates its own channel-instance-scoped instance`() {
        val index = sampleIndex()

        Container.build(listOf(index), overrides(index)).use { container ->
            val telegram = container.child("telegram", setOf("sample-plugin"))
            val session = telegram.get(key<Probe>("session"))

            assertEquals("session of " + container.get(key<Probe>("app")).report(), session.report())
            assertSame(session, telegram.get(key<Probe>("session")))
            assertNotSame(session, container.child("discord", setOf("sample-plugin")).get(key<Probe>("session")))
            assertFailsWith<DiException> { container.get(key<Probe>("session")) }
        }
    }

    @Test
    fun `the index binds no config section itself`() {
        val index = sampleIndex()

        val error = assertFailsWith<DiException> {
            Container.build(listOf(index), listOf(instanceBinding(key<String>("user"), "Ada", "test", "test")))
        }
        assertContains(error.message!!, "nothing binds sample.plugin.config.GreetingConfig")
    }

    // Generated index.

    @Test
    fun `the index declares every binding with its origin, scope, flags and dependencies`() {
        val probe = Probe::class.java.name

        assertEquals(
            mapOf(
                "App" to listOf(
                    "@Named(\"app\") sample.plugin.App SINGLETON <- " +
                        "greeter: INSTANCE @Named(\"polite\") sample.plugin.Greeter, " +
                        "user: INSTANCE @Named(\"user\") kotlin.String, tools: ALL $probe, " +
                        "audit: OPTIONAL sample.plugin.Audit, " +
                        "limits: INSTANCE kotlin.collections.Map<kotlin.String, kotlin.Int>",
                    "@Named(\"app\") $probe SINGLETON unmanaged <- @Binds: INSTANCE @Named(\"app\") sample.plugin.App",
                ),
                "EchoTool" to listOf(
                    "sample.plugin.EchoTool SINGLETON <- ",
                    "$probe SINGLETON multi unmanaged <- @Contribute: INSTANCE sample.plugin.EchoTool",
                ),
                "FixedClock" to listOf(
                    "sample.plugin.FixedClock SINGLETON <- ",
                    "sample.plugin.Clock SINGLETON unmanaged <- @Binds: INSTANCE sample.plugin.FixedClock",
                ),
                "Limits" to listOf(
                    "sample.plugin.Limits SINGLETON <- ",
                    "kotlin.collections.Map<kotlin.String, kotlin.Int> SINGLETON unmanaged <- " +
                        "@Binds: INSTANCE sample.plugin.Limits",
                ),
                "PoliteGreeter" to listOf(
                    "@Named(\"polite\") sample.plugin.PoliteGreeter SINGLETON <- " +
                        "config: INSTANCE sample.plugin.config.GreetingConfig, " +
                        "sample: INSTANCE sample.plugin.config.SampleConfig, clock: PROVIDER sample.plugin.Clock",
                    "@Named(\"polite\") sample.plugin.Greeter SINGLETON unmanaged <- " +
                        "@Binds: INSTANCE @Named(\"polite\") sample.plugin.PoliteGreeter",
                ),
                "Session" to listOf(
                    "@Named(\"session\") sample.plugin.Session CHANNEL_INSTANCE <- " +
                        "app: INSTANCE @Named(\"app\") $probe",
                    "@Named(\"session\") $probe CHANNEL_INSTANCE unmanaged <- " +
                        "@Binds: INSTANCE @Named(\"session\") sample.plugin.Session",
                ),
                "TimeTool" to listOf(
                    "sample.plugin.TimeTool SINGLETON <- clock: LAZY sample.plugin.Clock",
                    "$probe SINGLETON multi unmanaged <- @Contribute: INSTANCE sample.plugin.TimeTool",
                ),
            ),
            sampleIndex().bindings().groupBy({ simpleOrigin(it.origin) }, ::describe),
        )
    }

    @Test
    fun `the index lists the config sections with their relative paths`() {
        val sections = sampleIndex().configSections()

        assertEquals(
            listOf(
                "'' sample.plugin.config.SampleConfig from SampleConfig",
                "'greeting' sample.plugin.config.GreetingConfig from GreetingConfig",
            ),
            sections.map { "'${it.path}' ${it.key} from ${simpleOrigin(it.origin)}" },
        )
        assertEquals(
            listOf("sample.plugin.config.SampleConfig", "sample.plugin.config.GreetingConfig"),
            sections.map { it.deserializer.descriptor.serialName },
        )
    }

    @Test
    fun `the generated index lists sections without referring to ConfigSource`() {
        val generated = compileSample().generated("sample.plugin.SamplePluginIndex")

        assertContains(generated, "package sample.plugin\n")
        assertContains(generated, "override fun configSections(): List<ConfigSectionSpec<*>> = listOf(")
        assertContains(generated, "deserializer = sample.plugin.config.GreetingConfig.serializer(),")
        assertFalse("ConfigSource" in generated, generated)
    }

    @Test
    fun `section paths are relative to the module's config root`() {
        val compiled = compile(
            workingDir,
            source(
                "Paths.kt",
                """
                package sample

                import kotlinx.serialization.Serializable
                import org.foedusprogramme.alexandrite.sdk.config.ConfigSection

                @ConfigSection() @Serializable class Root
                @ConfigSection("cache") @Serializable class Cache
                @ConfigSection("cache.disk") @Serializable class Disk
                @ConfigSection("Web-hooks_2.retry") @Serializable class Retry
                @ConfigSection("cache.enabled") @Serializable class CacheSwitch
                """,
            ),
        )
        compiled.assertSucceeded()

        val index = compiled.indexes().single()
        assertEquals(
            listOf("", "Web-hooks_2.retry", "cache", "cache.disk", "cache.enabled"),
            index.configSections().map { it.path },
        )
        assertEquals("plugins.sample", index.configRoot)
    }

    @Test
    fun `a module without components or sections still gets an index`() {
        val compiled = compile(
            workingDir,
            source("Plain.kt", "package sample\n\nclass Plain\n"),
            options = mapOf(MODULE_OPTION to "sample", PACKAGE_OPTION to "sample"),
        )
        compiled.assertSucceeded()

        val index = compiled.indexes().single()
        assertEquals("sample", index.module)
        assertEquals(emptyList(), index.bindings())
        assertEquals(emptyList(), index.configSections())
        assertFalse("configSections" in compiled.generated("sample.SampleIndex"))
        Container.build(listOf(index)).close()
    }

    // Compilation settings.

    @Test
    fun `the index compiles in explicit API strict mode`() {
        val compiled = compile(
            workingDir,
            source(
                "Explicit.kt",
                """
                package sample

                import kotlinx.serialization.Serializable
                import org.foedusprogramme.alexandrite.sdk.config.ConfigSection
                import org.foedusprogramme.alexandrite.sdk.di.Singleton

                @Singleton
                public class Clock

                @Singleton
                internal class Greeter(private val clock: Clock, private val config: GreeterConfig)

                @ConfigSection
                @Serializable
                internal class GreeterConfig
                """,
            ),
            explicitApi = true,
        )

        compiled.assertSucceeded()
        assertEquals(2, compiled.indexes().single().bindings().size)
        assertEquals(1, compiled.indexes().single().configSections().size)
    }

    @Test
    fun `components of later rounds are indexed once every round has run`() {
        val generated = GeneratingProvider(
            """
            package sample

            @org.foedusprogramme.alexandrite.sdk.di.Singleton
            class Generated
            """.trimIndent(),
        )
        val compiled = compile(
            workingDir,
            source(
                "Uses.kt",
                """
                package sample

                import org.foedusprogramme.alexandrite.sdk.di.Singleton

                @Singleton
                class UsesGenerated(val generated: Generated)
                """,
            ),
            extraProcessor = generated,
        )
        compiled.assertSucceeded()

        val bindings = compiled.indexes().single().bindings()
        assertEquals(listOf("sample.Generated", "sample.UsesGenerated"), bindings.map { it.key.toString() })
        Container.build(listOf(compiled.indexes().single())).close()
    }

    private fun simpleOrigin(origin: String): String =
        origin.removeSuffix(" (module sample-plugin)").substringAfterLast('.')
}
