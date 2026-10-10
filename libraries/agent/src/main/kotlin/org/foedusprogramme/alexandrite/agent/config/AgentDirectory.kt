package org.foedusprogramme.alexandrite.agent.config

import org.foedusprogramme.alexandrite.agent.model.Endpoints
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.config.ConfigException
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.slf4j.LoggerFactory

internal class Agent(val id: AgentId, val config: AgentConfig, val attachments: List<Attachment>) {
    val name: String get() = config.name ?: id.value

    val model: ModelRef? = config.model?.let(ModelRef::parse)

    val reasoning: ReasoningEffort? = config.reasoning?.let(ReasoningEffort::of)

    val language: LanguageTag? = config.language?.let(LanguageTag::of)

    val linkedChats: List<List<ChatAddress>> = config.linkedChats.map { group -> group.map(ChatAddress::parse) }
}

/** An agent's attachment to a configured channel instance. */
internal class Attachment(val instance: ChannelInstanceId, val default: Boolean, val home: ChatAddress?)

/** The configured agents, and which of them serve each channel instance. */
@Singleton
internal class AgentDirectory(settings: AgentSettings, channels: ChannelDirectory, endpoints: Endpoints) {
    /** In config order. */
    val agents: List<Agent> =
        settings.agents.map { (id, config) -> resolved(AgentId(id), config, channels, endpoints) }

    private val byId = agents.associateBy { it.id }
    private val attached = agents.flatMap { agent -> agent.attachments.map { it.instance to agent } }
        .groupBy({ it.first }, { it.second })

    fun agent(id: AgentId): Agent? = byId[id]

    /** The agents that serve [instance], in config order. */
    fun attached(instance: ChannelInstanceId): List<Agent> = attached[instance].orEmpty()

    /** The agent that serves the chats of [instance] that choose no other, null when no agent serves it. */
    fun default(instance: ChannelInstanceId): Agent? {
        val agents = attached(instance)
        return agents.singleOrNull()
            ?: agents.firstOrNull { agent -> agent.attachments.any { it.instance == instance && it.default } }
    }
}

private fun resolved(id: AgentId, config: AgentConfig, channels: ChannelDirectory, endpoints: Endpoints): Agent {
    val path = "agent.agents.$id"
    val model = config.model?.let(ModelRef::parse)
    if (model != null && endpoints.endpoint(model.endpoint) == null) {
        val known = endpoints.ids.joinToString().ifEmpty { "none" }
        throw ConfigException(
            "$path.model",
            "no model provider contributes the endpoint '${model.endpoint}'. Endpoints: $known.",
        )
    }
    val attachments = config.channels.mapNotNull { (key, attachment) ->
        val instance = ChannelInstanceId.parse(key)
        val ofType = channels.instances.filter { it.type == instance.type }
        when {
            instance in channels.instances ->
                Attachment(instance, attachment.default, attachment.home?.let { homeChat(key, it) })

            ofType.isNotEmpty() -> throw ConfigException(
                "$path.channels.$key",
                "no channel instance $instance is configured. Instances of channel type ${instance.type}: " +
                    "${ofType.joinToString()}.",
            )

            else -> {
                logger.warn(
                    "Agent '{}' is attached to {}, but no instance of channel type {} is configured: the attachment " +
                        "is ignored",
                    id,
                    instance,
                    instance.type,
                )
                null
            }
        }
    }
    val instances = attachments.map { it.instance }.toSet()
    for (chat in config.linkedChats.flatten().map(ChatAddress::parse).filter { it.instance !in instances }) {
        logger.warn(
            "Agent '{}' links chat {}, but no instance of channel type {} is configured: the chat gets no messages",
            id,
            chat,
            chat.instance.type,
        )
    }
    return Agent(id, config, attachments)
}

private val logger = LoggerFactory.getLogger(AgentDirectory::class.java)
