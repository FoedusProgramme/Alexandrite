package org.foedusprogramme.alexandrite.ksp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.sdk.channel.Channel
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.di.container.Container
import org.foedusprogramme.alexandrite.sdk.di.container.PluginBindings
import org.foedusprogramme.alexandrite.sdk.di.container.Scope
import org.foedusprogramme.alexandrite.sdk.di.container.instanceBinding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChannelTest {
    private val plugin = source(
        "Chat.kt",
        """
        package sample.chat

        import kotlinx.serialization.Serializable
        import org.foedusprogramme.alexandrite.ksp.Probe
        import org.foedusprogramme.alexandrite.sdk.channel.*
        import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
        import org.foedusprogramme.alexandrite.sdk.config.ConfigSection
        import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
        import org.foedusprogramme.alexandrite.sdk.di.Contribute
        import org.foedusprogramme.alexandrite.sdk.plugin.Plugin

        @Plugin(name = "Chat")
        class ChatPlugin

        @ConfigSection @Serializable class ChatConfig(val api: String = "https://chat.example")

        @ChannelInstanceScoped @ConfigSection @Serializable class TokenConfig(val token: String)

        @ChannelInstanceScoped @ConfigSection("limits") @Serializable class LimitConfig(val perSecond: Int = 1)

        @ConfigSection("limits") @Serializable class SharedLimits(val total: Int = 30)

        @ChannelInstanceScoped
        @Contribute(Channel::class, name = "chat")
        class ChatChannel(
            private val instance: ChannelInstance,
            private val token: TokenConfig,
            private val limits: LimitConfig,
            private val config: ChatConfig,
        ) : Channel, Probe {
            override suspend fun capabilities(chat: ChatAddress) = ChannelCapabilities.builder().build()

            override suspend fun partsNeeded(chat: ChatAddress, text: String, markup: Markup) = 1

            override suspend fun openReply(request: ReplyRequest): ReplySink = TODO()

            override suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery =
                Delivery.Delivered(emptyList())

            override fun report(): String = "${'$'}{instance.id} ${'$'}{token.token} ${'$'}{limits.perSecond} ${'$'}{config.api}"
        }
        """,
    )

    private val compiled by lazy {
        compile(sharedDir, plugin, options = sampleOptions("chat-plugin")).also { it.assertSucceeded() }
    }

    private val index: PluginIndex by lazy { compiled.indexes().single() }

    @AfterAll
    fun close() {
        compiled.close()
    }

    @Test
    fun `the channel's binding and the descriptor carry its name, the channel type`() {
        val channel = index.bindings().single { it.key == key<Channel>() }

        assertEquals("chat", channel.name)
        assertEquals(
            JsonObject(mapOf(CHANNEL to JsonArray(listOf(JsonPrimitive("chat"))))),
            compiled.descriptor("chat-plugin")["contributionNames"],
        )
    }

    @Test
    fun `instance sections are listed with their scope after the plugin-wide ones`() {
        assertEquals(
            listOf(
                "'' SINGLETON sample.chat.ChatConfig",
                "'limits' SINGLETON sample.chat.SharedLimits",
                "'' CHANNEL_INSTANCE sample.chat.TokenConfig",
                "'limits' CHANNEL_INSTANCE sample.chat.LimitConfig",
            ),
            index.configSections().map { "'${it.path}' ${it.scope} ${it.key}" },
        )
    }

    @Test
    fun `a channel instance container creates the channel with its instance and its instance config`() {
        val sections = index.configSections().associateBy { it.key.toString().substringAfterLast('.') }
        val root = Container.build(
            listOf(
                PluginBindings(
                    "chat-plugin",
                    index.bindings() + listOf(sections.getValue("ChatConfig"), sections.getValue("SharedLimits"))
                        .map { it.decoded("{}", Scope.SINGLETON) },
                ),
            ),
        )
        val work = ChannelInstanceId(ChannelType("chat"), "work")
        val child = root.child(
            "$work",
            setOf("chat-plugin"),
            listOf(
                instanceBinding(key<ChannelInstance>(), Instance(work), "runtime", "instance", Scope.CHANNEL_INSTANCE),
                sections.getValue("TokenConfig").decoded("""{"token": "t-1"}""", Scope.CHANNEL_INSTANCE),
                sections.getValue("LimitConfig").decoded("""{"perSecond": 3}""", Scope.CHANNEL_INSTANCE),
            ),
        )

        val channel = child.getAll(key<Channel>()).single()

        assertEquals("chat:work t-1 3 https://chat.example", (channel as Probe).report())
        root.close()
    }

    private class Instance(override val id: ChannelInstanceId) : ChannelInstance {
        override val scope: CoroutineScope = CoroutineScope(Job())
    }

    private companion object {
        @TempDir
        lateinit var sharedDir: File
    }
}

private fun <T : Any> ConfigSectionSpec<T>.decoded(json: String, scope: Scope): Binding<T> =
    instanceBinding(key, Json.decodeFromString(deserializer, json), "chat-plugin", origin, scope)
