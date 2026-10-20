package org.unitedsoftware.alexandrite.agent.config

import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.model.Endpoints
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import java.time.ZoneId

/** An agent as configured, whose models other than its default count only where [known] knows their endpoints. */
internal class Agent(
    val id: AgentId,
    val config: AgentConfig,
    val attachments: List<Attachment>,
    known: (ModelRef) -> Boolean,
) {
    val name: String get() = config.name ?: id.value

    /** The agent's equal models, the first its default. */
    val models: List<ModelRef> = configuredModels(config).filterIndexed { index, model -> index == 0 || known(model) }

    val model: ModelRef? = models.firstOrNull()

    val reasoning: ReasoningEffort? = config.reasoning?.let(ReasoningEffort::of)

    val language: LanguageTag? = config.language?.let(LanguageTag::of)

    val zone: ZoneId? = config.zone?.let(ZoneId::of)

    val mode: PermissionMode? = config.permission.mode?.let(PermissionMode::of)

    /** Null when the agent has no model, or its permission section names one of an unknown endpoint: a person rules. */
    val judgeModel: ModelRef? = config.permission.judge?.let(ModelRef::parse)?.takeIf(known)
        ?: model.takeIf { config.permission.judge == null }

    /** The model of the agent's side questions, such as those about web pages. */
    val sideModel: ModelRef? = judgeModel ?: model

    /** The model that writes the agent's summaries, null for the model of the turn. */
    val summaryModel: ModelRef? = config.compaction.model?.let(ModelRef::parse)?.takeIf(known)

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

private fun configuredModels(config: AgentConfig): List<ModelRef> =
    (config.model?.let(::listOf) ?: config.models).map(ModelRef::parse)

/** The agent [id] as [config] describes it, whose models of unknown endpoints but its default are left out. */
private fun resolved(id: AgentId, config: AgentConfig, channels: ChannelDirectory, endpoints: Endpoints): Agent {
    val path = "agent.agents.$id"
    val known = { model: ModelRef -> endpoints.endpoint(model.endpoint) != null }
    val endpointIds = endpoints.ids.joinToString().ifEmpty { "none" }
    val warn = { key: String, model: ModelRef, outcome: String ->
        logger.warn(
            "{}.{} names the endpoint '{}', which no model provider contributes (endpoints: {}): {}",
            path,
            key,
            model.endpoint,
            endpointIds,
            outcome,
        )
    }
    configuredModels(config).forEachIndexed { index, model ->
        val key = if (config.model != null) "model" else "models[$index]"
        when {
            known(model) -> {}

            index == 0 -> throw ConfigException(
                "$path.$key",
                "no model provider contributes the endpoint '${model.endpoint}'. Endpoints: $endpointIds.",
            )

            else -> warn(key, model, "the agent goes on without $model")
        }
    }
    config.permission.judge?.let(ModelRef::parse)?.takeUnless(known)?.let {
        warn("permission.judge", it, "a person rules on the calls the judge would rule on")
    }
    config.compaction.model?.let(ModelRef::parse)?.takeUnless(known)?.let {
        warn("compaction.model", it, "the turn's model writes the summaries")
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
    return Agent(id, config, attachments, known)
}

private val logger = LoggerFactory.getLogger(AgentDirectory::class.java)
