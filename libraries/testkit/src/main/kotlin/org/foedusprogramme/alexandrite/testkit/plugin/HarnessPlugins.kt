package org.foedusprogramme.alexandrite.testkit.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.sdk.AlexandriteSdk
import org.foedusprogramme.alexandrite.sdk.channel.Channel
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.chat.ChatStateStore
import org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.di.container.Dependency
import org.foedusprogramme.alexandrite.sdk.di.container.DependencyKind
import org.foedusprogramme.alexandrite.sdk.di.container.Scope
import org.foedusprogramme.alexandrite.sdk.di.container.binding
import org.foedusprogramme.alexandrite.sdk.di.container.instanceBinding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.model.ModelEndpoint
import org.foedusprogramme.alexandrite.sdk.model.ModelProvider
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.InitiatedTurn
import org.foedusprogramme.alexandrite.sdk.turn.TurnInitiation
import org.foedusprogramme.alexandrite.sdk.turn.TurnSubmitter
import org.foedusprogramme.alexandrite.testkit.RecordingAgentControl
import org.foedusprogramme.alexandrite.testkit.RecordingChannel
import org.foedusprogramme.alexandrite.testkit.RecordingTurnInitiator
import org.foedusprogramme.alexandrite.testkit.RecordingTurnSubmitter
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.foedusprogramme.alexandrite.testkit.TestChatStates
import java.util.concurrent.CopyOnWriteArrayList

/** A plugin of the harness, whose config root is that of a third-party plugin. */
internal abstract class HarnessPlugin(id: String, name: String) : PluginIndex {
    override val info: PluginInfo =
        PluginInfo(id, name, "test", "", AlexandriteSdk.API_VERSION, emptyList(), javaClass.name)
    override val configRoot: String = PluginIds.thirdPartyRoot(id)

    override fun configSections(): List<ConfigSectionSpec<*>> = emptyList()
}

/** The plugin `testkit`, which binds the doubles the harness was given. */
internal class DoublesPlugin(
    models: List<ScriptedModel>,
    submitter: RecordingTurnSubmitter?,
    initiator: RecordingTurnInitiator?,
    control: RecordingAgentControl?,
    states: TestChatStates?,
) : HarnessPlugin(ID, "Test doubles") {
    private val bindings = buildList {
        if (models.isNotEmpty()) {
            val provider = object : ModelProvider {
                override val endpoints: List<ModelEndpoint> = models.toList()
            }
            add(instanceBinding(key<ModelProvider>(), provider, ID, "ScriptedModel provider", multi = true))
        }
        submitter?.let { add(instanceBinding(key<TurnSubmitter>(), it, ID, "RecordingTurnSubmitter")) }
        initiator?.let { recorder ->
            val initiation = object : TurnInitiation {
                override fun initiate(plugin: String, turn: InitiatedTurn) = recorder.record(plugin, turn)
            }
            add(instanceBinding(key<TurnInitiation>(), initiation, ID, "RecordingTurnInitiator"))
        }
        control?.let { add(instanceBinding(key<AgentControl>(), it, ID, "RecordingAgentControl")) }
        states?.let { add(instanceBinding(key<ChatStateStore>(), it.store, ID, "TestChatStates")) }
    }

    val empty: Boolean get() = bindings.isEmpty()

    override fun bindings(): List<Binding<*>> = bindings

    companion object {
        const val ID: String = "testkit"
    }
}

/** The settings of one instance of a recording channel. */
@Serializable
internal class RecordingChannelConfig(
    val admins: Set<String> = emptySet(),
    val partLength: Int = RecordingChannel.DEFAULT_PART_LENGTH,
) {
    init {
        require(partLength > 0) { "partLength must be positive, was $partLength" }
    }
}

/** The plugin `<type>-channel`, whose channel of [type] is a [RecordingChannel] per instance of [instances]. */
internal class RecordingChannelPlugin(
    val type: ChannelType,
    private val instances: Map<String, RecordingChannelConfig>,
) : HarnessPlugin("$type-channel", "Recording channel '$type'") {
    private val id = info.id
    private val settings = key<RecordingChannelConfig>(id)
    private val channel = key<RecordingChannel>(id)

    /** The channels of the run, in the order the runtime created them. */
    val created: MutableList<RecordingChannel> = CopyOnWriteArrayList()

    /** The config below the plugin's root. */
    fun config(): JsonObject = JsonObject(
        mapOf(
            PluginIds.INSTANCES_KEY to JsonObject(
                instances.mapValues { (_, config) ->
                    Json.encodeToJsonElement(RecordingChannelConfig.serializer(), config)
                },
            ),
        ),
    )

    override fun configSections(): List<ConfigSectionSpec<*>> = listOf(
        ConfigSectionSpec(
            settings,
            "",
            RecordingChannelConfig.serializer(),
            "RecordingChannelConfig",
            Scope.CHANNEL_INSTANCE,
        ),
    )

    override fun bindings(): List<Binding<*>> = listOf(
        binding(
            channel,
            id,
            "RecordingChannel",
            Scope.CHANNEL_INSTANCE,
            listOf(
                Dependency(key<ChannelInstance>(), DependencyKind.INSTANCE, "instance"),
                Dependency(settings, DependencyKind.INSTANCE, "config"),
                Dependency(key<TurnSubmitter>(), DependencyKind.OPTIONAL, "submitter"),
            ),
            managed = false,
        ) { r ->
            val config = r.get(settings)
            RecordingChannel(
                r.get(key<ChannelInstance>()).id,
                config.admins,
                config.partLength,
                r.getOrNull(key<TurnSubmitter>()),
            )
                .also(created::add)
        },
        binding(
            key<Channel>(),
            id,
            "RecordingChannel",
            Scope.CHANNEL_INSTANCE,
            listOf(Dependency(channel, DependencyKind.INSTANCE, "channel")),
            multi = true,
            managed = false,
            name = type.value,
        ) { r -> r.get(channel) },
        binding(
            key<StopWatch>(id),
            id,
            "RecordingChannel stop",
            Scope.CHANNEL_INSTANCE,
            listOf(Dependency(channel, DependencyKind.INSTANCE, "channel")),
        ) { r -> StopWatch(r.get(channel)) },
    )
}

/** Tells its channel when the instance stops. */
internal class StopWatch(private val channel: RecordingChannel) : Lifecycle {
    override fun onStop() {
        channel.stopped = true
    }
}
