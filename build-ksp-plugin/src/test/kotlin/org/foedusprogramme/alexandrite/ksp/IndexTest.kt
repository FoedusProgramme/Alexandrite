package org.foedusprogramme.alexandrite.ksp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.foedusprogramme.alexandrite.sdk.config.ConfigSource
import org.foedusprogramme.alexandrite.sdk.config.JsonConfigSource
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

    private val sample = source(
        "Sample.kt",
        """
        package sample

        import kotlinx.serialization.Serializable
        import org.foedusprogramme.alexandrite.ksp.Probe
        import org.foedusprogramme.alexandrite.sdk.config.ConfigSection
        import org.foedusprogramme.alexandrite.sdk.di.Binds
        import org.foedusprogramme.alexandrite.sdk.di.ChannelScoped
        import org.foedusprogramme.alexandrite.sdk.di.Contribute
        import org.foedusprogramme.alexandrite.sdk.di.Inject
        import org.foedusprogramme.alexandrite.sdk.di.Named
        import org.foedusprogramme.alexandrite.sdk.di.Singleton

        @ConfigSection("sample.greeting")
        @Serializable
        data class GreetingConfig(val word: String = "Hello")

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
        class PoliteGreeter(private val config: GreetingConfig, private val clock: () -> Clock) : Greeter {
            override fun greet(name: String): String = config.word + " " + name + ", it is " + clock().now()
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

        @ChannelScoped
        @Named("session")
        @Binds(Probe::class)
        class Session(@Named("app") private val app: Probe) : Probe {
            override fun report(): String = "session of " + app.report()
        }
        """,
    )

    private val config =
        JsonConfigSource(Json.parseToJsonElement("""{"sample": {"greeting": {"word": "Hi"}}}""").jsonObject)

    private val overrides = listOf(
        instanceBinding(key<ConfigSource>(), config, "test"),
        instanceBinding(key<String>("user"), "Ada", "test"),
    )

    private fun sampleIndex(): ModuleIndex {
        val compiled = compile(
            workingDir,
            sample,
            options = mapOf(MODULE_OPTION to "sample-plugin", CONFIG_ROOT_OPTION to "channels.sample"),
        )
        compiled.assertSucceeded()
        return compiled.indexes().single()
    }

    // End to end.

    @Test
    fun `ServiceLoader finds the generated index with the module name and config root`() {
        val index = sampleIndex()

        assertEquals("$GENERATED_PACKAGE.SamplePluginIndex", index.javaClass.name)
        assertEquals("sample-plugin", index.module)
        assertEquals("channels.sample", index.configRoot)
    }

    @Test
    fun `the container resolves the generated graph`() {
        Container.build(listOf(sampleIndex()), overrides).use { container ->
            assertEquals(
                "Hi Ada, it is noon; tools=[echo, time noon]; audit=null; rounds=3",
                container.get(key<Probe>("app")).report(),
            )
            assertEquals(listOf("echo", "time noon"), container.getAll(key<Probe>()).map { it.report() })
        }
    }

    @Test
    fun `a channel container creates its own channel-scoped instance`() {
        Container.build(listOf(sampleIndex()), overrides).use { container ->
            val telegram = container.child("telegram")
            val session = telegram.get(key<Probe>("session"))

            assertEquals("session of " + container.get(key<Probe>("app")).report(), session.report())
            assertSame(session, telegram.get(key<Probe>("session")))
            assertNotSame(session, container.child("discord").get(key<Probe>("session")))
            assertFailsWith<DiException> { container.get(key<Probe>("session")) }
        }
    }

    @Test
    fun `the index declares every binding with its origin, scope, flags and dependencies`() {
        val probe = Probe::class.java.name

        assertEquals(
            mapOf(
                "sample.GreetingConfig" to listOf(
                    "sample.GreetingConfig SINGLETON unmanaged <- configSource: INSTANCE ${ConfigSource::class.java.name}",
                ),
                "sample.App" to listOf(
                    "@Named(\"app\") sample.App SINGLETON <- greeter: INSTANCE @Named(\"polite\") sample.Greeter, " +
                        "user: INSTANCE @Named(\"user\") kotlin.String, tools: ALL $probe, " +
                        "audit: OPTIONAL sample.Audit, limits: INSTANCE kotlin.collections.Map<kotlin.String, kotlin.Int>",
                    "@Named(\"app\") $probe SINGLETON unmanaged <- @Binds: INSTANCE @Named(\"app\") sample.App",
                ),
                "sample.EchoTool" to listOf(
                    "sample.EchoTool SINGLETON <- ",
                    "$probe SINGLETON multi unmanaged <- @Contribute: INSTANCE sample.EchoTool",
                ),
                "sample.FixedClock" to listOf(
                    "sample.FixedClock SINGLETON <- ",
                    "sample.Clock SINGLETON unmanaged <- @Binds: INSTANCE sample.FixedClock",
                ),
                "sample.Limits" to listOf(
                    "sample.Limits SINGLETON <- ",
                    "kotlin.collections.Map<kotlin.String, kotlin.Int> SINGLETON unmanaged <- " +
                        "@Binds: INSTANCE sample.Limits",
                ),
                "sample.PoliteGreeter" to listOf(
                    "@Named(\"polite\") sample.PoliteGreeter SINGLETON <- config: INSTANCE sample.GreetingConfig, " +
                        "clock: PROVIDER sample.Clock",
                    "@Named(\"polite\") sample.Greeter SINGLETON unmanaged <- " +
                        "@Binds: INSTANCE @Named(\"polite\") sample.PoliteGreeter",
                ),
                "sample.Session" to listOf(
                    "@Named(\"session\") sample.Session CHANNEL <- app: INSTANCE @Named(\"app\") $probe",
                    "@Named(\"session\") $probe CHANNEL unmanaged <- " +
                        "@Binds: INSTANCE @Named(\"session\") sample.Session",
                ),
                "sample.TimeTool" to listOf(
                    "sample.TimeTool SINGLETON <- clock: LAZY sample.Clock",
                    "$probe SINGLETON multi unmanaged <- @Contribute: INSTANCE sample.TimeTool",
                ),
            ),
            sampleIndex().bindings().groupBy({ it.origin.removeSuffix(" (module sample-plugin)") }, ::describe),
        )
    }

    private fun describe(binding: Binding<*>): String {
        val flags = listOfNotNull("multi".takeIf { binding.multi }, "unmanaged".takeIf { !binding.managed })
        val dependencies = binding.dependencies.joinToString { "${it.parameter}: ${it.kind} ${it.key}" }
        return (listOf(binding.key.toString(), binding.scope.toString()) + flags).joinToString(" ") +
            " <- " + dependencies
    }

    // Module options.

    @Test
    fun `a module without components still gets an index`() {
        val compiled = compile(workingDir, source("Plain.kt", "package sample\n\nclass Plain\n"))
        compiled.assertSucceeded()

        val index = compiled.indexes().single()
        assertEquals("sample", index.module)
        assertEquals(emptyList(), index.bindings())
        Container.build(listOf(index)).close()
    }

    @Test
    fun `the config root defaults to plugins dot module`() {
        val compiled = compile(workingDir, source("Plain.kt", "package sample\n\nclass Plain\n"))

        assertEquals("plugins.sample", compiled.indexes().single().configRoot)
    }

    @Test
    fun `the index class is named after the module and listed as a service`() {
        val compiled = compile(
            workingDir,
            source("Plain.kt", "package sample\n\nclass Plain\n"),
            options = mapOf(MODULE_OPTION to "alexandrite-channel-telegram", CONFIG_ROOT_OPTION to "channels.telegram"),
        )
        compiled.assertSucceeded()

        assertEquals("$GENERATED_PACKAGE.AlexandriteChannelTelegramIndex\n", compiled.service())
        assertContains(
            compiled.generated("AlexandriteChannelTelegramIndex"),
            "public class AlexandriteChannelTelegramIndex",
        )
    }

    @Test
    fun `a missing module option fails with how to set it`() {
        val compiled = compile(workingDir, source("Plain.kt", "package sample\n\nclass Plain\n"), options = emptyMap())

        assertFalse(compiled.succeeded)
        assertContains(compiled.messages, "The KSP option 'alexandrite.module' is not set")
        assertContains(compiled.messages, "ksp { arg(\"alexandrite.module\", \"my-plugin\") }")
    }

    @Test
    fun `a module name that makes no class name fails`() {
        val compiled = compile(
            workingDir,
            source("Plain.kt", "package sample\n\nclass Plain\n"),
            options = mapOf(MODULE_OPTION to "1st plugin"),
        )

        assertFalse(compiled.succeeded)
        assertContains(compiled.messages, "The KSP option 'alexandrite.module' is '1st plugin'")
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

                import org.foedusprogramme.alexandrite.sdk.di.Singleton

                @Singleton
                public class Clock

                @Singleton
                internal class Greeter(private val clock: Clock)
                """,
            ),
            explicitApi = true,
        )

        compiled.assertSucceeded()
        assertEquals(2, compiled.indexes().single().bindings().size)
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
}
