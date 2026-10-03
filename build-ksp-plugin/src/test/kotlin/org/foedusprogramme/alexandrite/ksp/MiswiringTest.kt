package org.foedusprogramme.alexandrite.ksp

import kotlin.test.Test
import kotlin.test.assertFalse

class MiswiringTest : FailingSamples() {
    private val tool = "org.foedusprogramme.alexandrite.sdk.tool.Tool"
    private val hook = "org.foedusprogramme.alexandrite.sdk.hook.Hook"

    private val bases = """
        abstract class BaseTool : Tool {
            override val definition: ToolDefinition get() = TODO()

            override suspend fun execute(arguments: kotlinx.serialization.json.JsonObject, context: ToolContext) =
                ToolResult("")
        }
        abstract class BaseObserver : ObserverHook<String> {
            override val point = ObserverPoint<String>("test.seen")

            override suspend fun observe(payload: String) {}
        }
    """.trimIndent()

    // SPIs.

    @Test
    fun `a concrete class that implements an SPI without contributing to it is rejected`() {
        val messages = errors(
            bases + "\n" +
                """
                class Echo : BaseTool()
                @Singleton class Listener : BaseObserver()
                @Contribute(Runnable::class) class Both : BaseTool(), Runnable {
                    override fun run() {}
                }
                object Single : BaseTool()
                interface SpecialTool : Tool
                abstract class AbstractTool : BaseTool()
                @Contribute(Tool::class) class Contributed : BaseTool()
                @Contribute(Hook::class) class Observed : BaseObserver()
                """.trimIndent(),
        )

        assertReported(
            messages,
            "sample.Echo implements $tool, but does not contribute to it, so it is never used as one",
            "Annotate it with @Contribute(Tool::class)",
            at = "class Echo",
        )
        assertReported(
            messages,
            "sample.Listener implements $hook, but does not contribute to it",
            at = "class Listener",
        )
        assertReported(
            messages,
            "sample.Both implements $tool",
            "Add Tool::class to its @Contribute",
            at = "class Both",
        )
        assertReported(messages, "sample.Single implements $tool", at = "object Single")
        for (exempt in listOf("BaseTool", "BaseObserver", "SpecialTool", "AbstractTool", "Contributed", "Observed")) {
            assertFalse("sample.$exempt implements" in messages, messages)
        }
    }

    @Test
    fun `an SPI listed in @Binds is rejected`() {
        val messages = errors(bases + "\n" + "@Binds(Tool::class) @Contribute(Tool::class) class Bound : BaseTool()")

        assertReported(
            messages,
            "sample.Bound lists $tool in @Binds, but $tool is an SPI whose implementations are contributed, not bound",
            "Use @Contribute(Tool::class) instead",
            at = "class Bound",
        )
    }

    @Test
    fun `a subtype of an SPI listed in @Contribute is rejected`() {
        val messages = errors(
            bases + "\n" +
                """
                @Contribute(ObserverHook::class, Hook::class) class Watcher : BaseObserver()
                @Contribute(BaseTool::class, Tool::class) class Narrow : BaseTool()
                """.trimIndent(),
        )

        assertReported(
            messages,
            "sample.Watcher lists org.foedusprogramme.alexandrite.sdk.hook.ObserverHook in @Contribute, but " +
                "org.foedusprogramme.alexandrite.sdk.hook.ObserverHook is a subtype of $hook",
            "Use @Contribute(Hook::class) instead",
            at = "class Watcher",
        )
        assertReported(
            messages,
            "sample.Narrow lists sample.BaseTool in @Contribute, but sample.BaseTool is a subtype of $tool",
            "Use @Contribute(Tool::class) instead",
            at = "class Narrow",
        )
    }

    @Test
    fun `a provider of an SPI implementation must contribute it`() {
        val messages = errors(
            bases + "\n" +
                """
                class Echo : BaseTool()
                class Shout : BaseTool()
                @Provides fun tool(): Tool = Echo()
                @Provides fun echo(): Echo = Echo()
                @Provides @Contribute(Tool::class) fun shout(): Shout = Shout()
                """.trimIndent(),
        )

        assertReported(
            messages,
            "sample.tool() returns $tool itself, but $tool is an SPI whose implementations are contributed",
            "Return the implementing type and annotate the function with @Contribute(Tool::class)",
            at = "fun tool",
        )
        assertReported(
            messages,
            "sample.echo() returns sample.Echo, which implements $tool, but does not contribute it",
            at = "fun echo",
        )
        assertReported(messages, "sample.Echo implements $tool", at = "class Echo")
        assertFalse("sample.Shout implements" in messages, messages)
        assertFalse("sample.shout() returns" in messages, messages)
    }

    // Same-module dependencies.

    @Test
    fun `a dependency on a class of the module that nothing binds is rejected`() {
        val messages = errors(
            """
            class Settings
            @ConfigSection("x") @Serializable class Section(val size: Int = 0)
            @Singleton class Engine
            class Made
            @Provides fun made(): Made = Made()
            interface Port
            abstract class Base
            @Singleton class Server(
                settings: Settings,
                maybe: Settings?,
                later: Lazy<Settings>,
                make: () -> Settings,
                all: List<Settings>,
                section: Section,
                engine: Engine,
                made: Made,
                port: Port,
                base: Base,
                clock: java.time.Clock,
            )
            @Provides @Named("fresh") fun fresh(settings: Settings): Engine = Engine()
            """,
        )

        val unannotated = "has type sample.Settings, a class of this module that is not a component, a config " +
            "section or returned by a @Provides function"
        assertReported(
            messages,
            "Parameter 'settings' of sample.Server $unannotated",
            "Annotate sample.Settings with @Singleton, or provide it with a @Provides function",
            at = "settings: Settings,",
        )
        assertReported(messages, "Parameter 'maybe' of sample.Server $unannotated", at = "maybe: Settings?")
        assertReported(messages, "Parameter 'later' of sample.Server $unannotated", at = "later: Lazy<Settings>")
        assertReported(messages, "Parameter 'make' of sample.Server $unannotated", at = "make: () -> Settings")
        assertReported(messages, "Parameter 'settings' of sample.fresh() $unannotated", at = "fun fresh")
        for (parameter in listOf("all", "section", "engine", "made", "port", "base", "clock")) {
            assertFalse("Parameter '$parameter' of sample.Server" in messages, messages)
        }
    }

    @Test
    fun `a singleton that depends on a channel-instance-scoped binding of the module is rejected`() {
        val messages = errors(
            """
            interface Listener
            @ChannelInstanceScoped class Session
            @ChannelInstanceScoped @Binds(Runnable::class) class Job : Runnable {
                override fun run() {}
            }
            @ChannelInstanceScoped @Contribute(Listener::class) class Ear : Listener
            @Provides @ChannelInstanceScoped @Named("chat") fun chat(): Clock = Clock()
            @Singleton class Registry(
                session: Session,
                job: Lazy<Runnable>,
                listeners: List<Listener>,
                @Named("chat") clock: () -> Clock,
            )
            @Provides fun registryClock(session: Session?): java.time.Clock = java.time.Clock.systemUTC()
            @ChannelInstanceScoped class Fine(session: Session, job: Runnable, listeners: List<Listener>)
            """,
        )

        assertReported(
            messages,
            "Singleton sample.Registry depends on channel-instance-scoped sample.Session through parameter 'session'",
            "Annotate sample.Registry with @ChannelInstanceScoped or drop the dependency",
            at = "session: Session,",
        )
        assertReported(messages, "on channel-instance-scoped sample.Job through parameter 'job'", at = "job: Lazy")
        assertReported(
            messages,
            "on channel-instance-scoped sample.Ear through parameter 'listeners'",
            at = "listeners",
        )
        assertReported(messages, "on channel-instance-scoped sample.chat() through parameter 'clock'", at = "clock: ()")
        assertReported(
            messages,
            "Singleton sample.registryClock() depends on channel-instance-scoped sample.Session",
            at = "fun registryClock",
        )
        assertFalse("Singleton sample.Fine" in messages, messages)
    }

    // Module-local services.

    @Test
    fun `a module-local service cannot be named`() {
        val messages = errors("@Singleton class Store(@Named(\"other\") val files: PluginFiles)")

        assertReported(
            messages,
            "Parameter 'files' of sample.Store has type org.foedusprogramme.alexandrite.sdk.runtime.PluginFiles, " +
                "which every module gets its own instance of under the module's name, so it cannot be @Named",
            at = "class Store",
        )
    }
}
