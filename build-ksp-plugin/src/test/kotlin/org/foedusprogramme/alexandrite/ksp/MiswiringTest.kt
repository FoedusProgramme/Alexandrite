package org.foedusprogramme.alexandrite.ksp

import kotlin.test.Test

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
        assertErrors(
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
            "class Echo" to Messages.uncontributed("sample.Echo", tool, contributes = false, isObject = false),
            "class Listener" to Messages.uncontributed("sample.Listener", hook, contributes = false, isObject = false),
            "class Both" to Messages.uncontributed("sample.Both", tool, contributes = true, isObject = false),
            "object Single" to Messages.uncontributed("sample.Single", tool, contributes = false, isObject = true),
        )
    }

    @Test
    fun `an SPI listed in @Binds is rejected`() {
        assertErrors(
            bases + "\n" + "@Binds(Tool::class) @Contribute(Tool::class) class Bound : BaseTool()",
            "class Bound" to Messages.boundSpi("sample.Bound", tool),
        )
    }

    @Test
    fun `a subtype of an SPI listed in @Contribute is rejected`() {
        assertErrors(
            bases + "\n" +
                """
                @Contribute(ObserverHook::class, Hook::class) class Watcher : BaseObserver()
                @Contribute(BaseTool::class, Tool::class) class Narrow : BaseTool()
                """.trimIndent(),
            "class Watcher" to Messages.contributedSubtype(
                "sample.Watcher",
                "org.foedusprogramme.alexandrite.sdk.hook.ObserverHook",
                listOf(hook),
            ),
            "class Narrow" to Messages.contributedSubtype("sample.Narrow", "sample.BaseTool", listOf(tool)),
        )
    }

    @Test
    fun `a provider of an SPI implementation must contribute it, and only the provider is reported`() {
        assertErrors(
            bases + "\n" +
                """
                class Echo : BaseTool()
                class Shout : BaseTool()
                @Provides fun tool(): Tool = Echo()
                @Provides fun echo(): Echo = Echo()
                @Provides @Contribute(Tool::class) fun shout(): Shout = Shout()
                """.trimIndent(),
            "fun tool" to Messages.providedSpi("sample.tool()", tool),
            "fun echo" to Messages.uncontributedProvider("sample.echo()", "sample.Echo", tool, contributes = false),
        )
    }

    @Test
    fun `a channel-instance-scoped hook is rejected`() {
        assertErrors(
            bases + "\n" +
                """
                @ChannelInstanceScoped @Contribute(Hook::class) class Scoped : BaseObserver()
                @ChannelInstanceScoped @Contribute(Tool::class) class ScopedTool : BaseTool()
                object Observers {
                    @Provides @ChannelInstanceScoped @Contribute(Hook::class) fun observer(): BaseObserver = Scoped()
                }
                """.trimIndent(),
            "class Scoped " to Messages.channelInstanceHook("sample.Scoped"),
            "fun observer" to Messages.channelInstanceHook("sample.Observers.observer()"),
        )
    }

    // Same-plugin dependencies.

    @Test
    fun `a dependency on a class of the plugin that nothing binds is rejected`() {
        assertErrors(
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
            "settings: Settings," to Messages.unannotatedDependency("settings", "sample.Server", "sample.Settings"),
            "maybe: Settings?" to Messages.unannotatedDependency("maybe", "sample.Server", "sample.Settings"),
            "later: Lazy<Settings>" to Messages.unannotatedDependency("later", "sample.Server", "sample.Settings"),
            "make: () -> Settings" to Messages.unannotatedDependency("make", "sample.Server", "sample.Settings"),
            "fun fresh" to
                Messages.unannotatedDependency("settings", "sample.fresh(sample.Settings)", "sample.Settings"),
        )
    }

    @Test
    fun `a singleton that depends on a channel-instance-scoped binding of the plugin is rejected`() {
        assertErrors(
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
            "session: Session," to Messages.scopeBreak("session", "sample.Registry", listOf("sample.Session")),
            "job: Lazy" to Messages.scopeBreak("job", "sample.Registry", listOf("sample.Job")),
            "listeners: List" to Messages.scopeBreak("listeners", "sample.Registry", listOf("sample.Ear")),
            "clock: ()" to Messages.scopeBreak("clock", "sample.Registry", listOf("sample.chat()")),
            "fun registryClock" to
                Messages.scopeBreak("session", "sample.registryClock(sample.Session?)", listOf("sample.Session")),
        )
    }

    @Test
    fun `a singleton that depends on the channel instance or an instance section is rejected`() {
        assertErrors(
            """
            @Plugin(name = "Chat", channelType = "chat") class ChatPlugin
            @ChannelInstanceScoped @ConfigSection @Serializable class Token(val token: String = "")
            @ChannelInstanceScoped @Contribute(Channel::class) class Scoped(instance: ChannelInstance, token: Token) :
                BaseChannel()
            @Singleton class Registry(instance: ChannelInstance, token: Token?)
            """.trimIndent() + "\n" + channelBase,
            "class Registry" to Messages.scopeBreak("instance", "sample.Registry", listOf(CHANNEL_INSTANCE)),
            "class Registry" to Messages.scopeBreak("token", "sample.Registry", listOf("sample.Token")),
            entry = false,
        )
    }

    // Channels.

    private val channelBase = """
        abstract class BaseChannel : Channel {
            override suspend fun capabilities(chat: ChatAddress) = ChannelCapabilities.builder().build()

            override suspend fun partsNeeded(chat: ChatAddress, text: String, markup: Markup) = 1

            override suspend fun openReply(request: ReplyRequest): ReplySink = TODO()

            override suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery =
                Delivery.Delivered(emptyList())
        }
    """.trimIndent()

    @Test
    fun `a channel contributed by a singleton is rejected`() {
        assertErrors(
            """
            @Plugin(name = "Chat", channelType = "chat") class ChatPlugin
            @Contribute(Channel::class) class Plain : BaseChannel()
            @Singleton @Contribute(Channel::class) class Single : BaseChannel()
            @ChannelInstanceScoped @Contribute(Channel::class) class Scoped : BaseChannel()
            object Channels {
                @Provides @Contribute(Channel::class) fun provided(): BaseChannel = Scoped()
            }
            """.trimIndent() + "\n" + channelBase,
            "class Plain" to Messages.singletonChannel("sample.Plain", provider = false),
            "class Single" to Messages.singletonChannel("sample.Single", provider = false),
            "fun provided" to Messages.singletonChannel("sample.Channels.provided()", provider = true),
            entry = false,
        )
    }

    @Test
    fun `a plugin that declares a channel type contributes a channel`() {
        assertErrors(
            "@Plugin(name = \"Chat\", channelType = \"chat\") class ChatPlugin",
            "class ChatPlugin" to Messages.missingChannel("sample.ChatPlugin", "chat"),
            entry = false,
        )
    }

    @Test
    fun `a plugin contributes one channel per channel instance`() {
        assertErrors(
            """
            @Plugin(name = "Chat", channelType = "chat") class ChatPlugin
            @ChannelInstanceScoped @Contribute(Channel::class) class First : BaseChannel()
            object Channels {
                @Provides @ChannelInstanceScoped @Contribute(Channel::class) fun second(): BaseChannel = First()
            }
            """.trimIndent() + "\n" + channelBase,
            "class First" to Messages.severalChannels("chat", listOf("sample.Channels.second()", "sample.First")),
            entry = false,
        )
    }

    @Test
    fun `a channel or an instance section in a plugin without a channel type is rejected`() {
        assertErrors(
            """
            @ChannelInstanceScoped @Contribute(Channel::class) class Scoped : BaseChannel()
            @ChannelInstanceScoped @ConfigSection @Serializable class Token(val token: String = "")
            """.trimIndent() + "\n" + channelBase,
            "class Scoped" to Messages.untypedChannel("sample.Scoped", "sample.SamplePlugin"),
            "class Token" to Messages.untypedInstanceSection("sample.Token", "sample.SamplePlugin"),
        )
    }

    // Plugin-local services.

    @Test
    fun `a plugin-local service cannot be named`() {
        assertErrors(
            "@Singleton class Store(@Named(\"other\") val files: PluginFiles)",
            "class Store" to Messages.namedPluginLocal(
                "files",
                "sample.Store",
                "org.foedusprogramme.alexandrite.sdk.plugin.PluginFiles",
            ),
        )
    }
}
