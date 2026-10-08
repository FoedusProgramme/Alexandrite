package org.foedusprogramme.alexandrite.ksp

import kotlin.test.Test

class MiswiringTest : FailingSamples() {
    private val tool = "org.foedusprogramme.alexandrite.sdk.tool.Tool"
    private val hook = "org.foedusprogramme.alexandrite.sdk.hook.Hook"
    private val store = "org.foedusprogramme.alexandrite.sdk.store.ChatStateStore"

    private val baseStore = """
        abstract class BaseStore : $store {
            override suspend fun read(
                plugin: String,
                name: String,
                agent: org.foedusprogramme.alexandrite.sdk.chat.AgentId?,
                chat: ChatAddress,
            ): String? = null

            override suspend fun write(
                plugin: String,
                name: String,
                agent: org.foedusprogramme.alexandrite.sdk.chat.AgentId?,
                chat: ChatAddress,
                json: String?,
            ) {}
        }
    """.trimIndent()

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
    fun `a concrete class that implements a bound SPI without binding it is rejected`() {
        assertErrors(
            baseStore + "\n" +
                """
                class Loose : BaseStore()
                @Singleton class Unbound : BaseStore()
                @Binds(Runnable::class) class Both : BaseStore(), Runnable {
                    override fun run() {}
                }
                object Single : BaseStore()
                @Binds($store::class) class Bound : BaseStore()
                """.trimIndent(),
            "class Loose" to Messages.unbound("sample.Loose", store, binds = false, isObject = false),
            "class Unbound" to Messages.unbound("sample.Unbound", store, binds = false, isObject = false),
            "class Both" to Messages.unbound("sample.Both", store, binds = true, isObject = false),
            "object Single" to Messages.unbound("sample.Single", store, binds = false, isObject = true),
        )
    }

    @Test
    fun `a bound SPI listed in @Contribute is rejected`() {
        assertErrors(
            baseStore + "\n" + "@Binds($store::class) @Contribute($store::class) class Twice : BaseStore()",
            "class Twice" to Messages.contributedBoundSpi("sample.Twice", store),
        )
    }

    @Test
    fun `a provider of a bound SPI implementation must bind it, and one that returns the SPI binds it`() {
        assertErrors(
            baseStore + "\n" +
                """
                class Files : BaseStore()
                class Memory : BaseStore()
                class Cache : BaseStore()
                object Stores {
                    @Provides fun store(): $store = Files()
                    @Provides fun memory(): Memory = Memory()
                    @Provides @Named("cache") @Binds($store::class) fun cache(): Cache = Cache()
                }
                """.trimIndent(),
            "fun memory" to
                Messages.unboundProvider("sample.Stores.memory()", "sample.Memory", store, binds = false),
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
            @ChannelInstanceScoped @ConfigSection @Serializable class Token(val token: String = "")
            @ChannelInstanceScoped @Contribute(Channel::class, name = "chat")
            class Scoped(instance: ChannelInstance, token: Token) : BaseChannel()
            @Singleton class Registry(instance: ChannelInstance, token: Token?)
            """.trimIndent() + "\n" + channelBase,
            "class Registry" to Messages.scopeBreak("instance", "sample.Registry", listOf(CHANNEL_INSTANCE)),
            "class Registry" to Messages.scopeBreak("token", "sample.Registry", listOf("sample.Token")),
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
            @Contribute(Channel::class, name = "chat") class Plain : BaseChannel()
            @Singleton @Contribute(Channel::class, name = "chat") class Single : BaseChannel()
            @ChannelInstanceScoped @Contribute(Channel::class, name = "chat") class Scoped : BaseChannel()
            object Channels {
                @Provides @Contribute(Channel::class, name = "chat") fun provided(): BaseChannel = Scoped()
            }
            """.trimIndent() + "\n" + channelBase,
            "class Plain" to Messages.singletonChannel("sample.Plain", provider = false),
            "class Single" to Messages.singletonChannel("sample.Single", provider = false),
            "fun provided" to Messages.singletonChannel("sample.Channels.provided()", provider = true),
        )
    }

    @Test
    fun `a contribution to a named SPI carries a name, and a contribution to any other type carries none`() {
        assertErrors(
            bases + "\n" +
                """
                interface Listener
                @ChannelInstanceScoped @Contribute(Channel::class) class Unnamed : BaseChannel()
                @ChannelInstanceScoped @Contribute(Channel::class, name = " ") class Blank : BaseChannel()
                @Contribute(Tool::class, name = "echo") class Echo : BaseTool()
                @Contribute(Listener::class, name = "ear") class Ear : Listener
                object Channels {
                    @Provides @ChannelInstanceScoped @Contribute(Channel::class) fun provided(): BaseChannel = Blank()
                }
                """.trimIndent() + "\n" + channelBase,
            "class Unnamed" to Messages.unnamedContribution("sample.Unnamed", CHANNEL),
            "class Blank" to Messages.unnamedContribution("sample.Blank", CHANNEL),
            "class Echo" to Messages.namedContribution("sample.Echo", tool, "echo"),
            "class Ear" to Messages.namedContribution("sample.Ear", "sample.Listener", "ear"),
            "fun provided" to Messages.unnamedContribution("sample.Channels.provided()", CHANNEL),
        )
    }

    @Test
    fun `a channel is named after a channel type`() {
        assertErrors(
            """
            @ChannelInstanceScoped @Contribute(Channel::class, name = "Tele_gram") class Chat : BaseChannel()
            """.trimIndent() + "\n" + channelBase,
            "class Chat" to Messages.malformedChannelType("sample.Chat", "Tele_gram"),
        )
    }

    @Test
    fun `a plugin contributes one channel, whatever the names`() {
        assertErrors(
            """
            @ChannelInstanceScoped @Contribute(Channel::class, name = "chat") class First : BaseChannel()
            object Channels {
                @Provides @ChannelInstanceScoped @Contribute(Channel::class, name = "talk")
                fun second(): BaseChannel = First()
            }
            """.trimIndent() + "\n" + channelBase,
            "class First" to Messages.severalChannels(listOf("sample.Channels.second()", "sample.First")),
        )
    }

    @Test
    fun `an instance section in a plugin that contributes no channel is rejected`() {
        assertErrors(
            "@ChannelInstanceScoped @ConfigSection @Serializable class Token(val token: String = \"\")",
            "class Token" to Messages.instanceSectionWithoutChannel("sample.Token"),
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
