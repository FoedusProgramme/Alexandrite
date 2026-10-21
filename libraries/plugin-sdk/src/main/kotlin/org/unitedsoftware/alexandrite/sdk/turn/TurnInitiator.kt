package org.unitedsoftware.alexandrite.sdk.turn

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.di.PluginLocal
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.tool.Mandate

/** Starts turns that are not user input in the plugin's name, which carry no user's authority. */
@PluginLocal
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface TurnInitiator {
    /**
     * Queues [turn] with the queue, capacity and outcome rules of a submission, and returns at once.
     *
     * Throws [IllegalArgumentException] for a turn of a kind in [TurnKind.agentStarted].
     */
    public fun initiate(turn: InitiatedTurn): Admission

    /** Ends [mandate], one of this plugin's, so that no later turn runs as its operator. */
    public suspend fun revoke(mandate: Mandate) {}
}

/** Throws the [IllegalArgumentException] of [TurnInitiator.initiate] when [plugin] may not initiate [turn]. */
@InternalAlexandriteApi
public fun requireInitiable(plugin: String, turn: InitiatedTurn) {
    require(turn.kind !in TurnKind.agentStarted) {
        "Plugin '$plugin' cannot initiate a turn of kind ${turn.kind}: the agent starts approval, agent message " +
            "and job turns itself."
    }
}

/** The agent's side of every plugin's [TurnInitiator]. */
@InternalAlexandriteApi
public interface TurnInitiation {
    /** Queues [turn] for [plugin], whose id its origin records, with the refusals of [TurnInitiator.initiate]. */
    public fun initiate(plugin: String, turn: InitiatedTurn): Admission

    /** Ends [mandate] when [plugin] holds it. */
    public suspend fun revoke(plugin: String, mandate: Mandate) {}
}

/** A turn that a plugin starts, answering its initiator and sending its final reply along [route]. */
@Poko
public class InitiatedTurn private constructor(
    /** The turn's policy home. */
    public val chat: ChatAddress,
    /** The agent the turn runs as, null for the agent that serves [chat]. */
    public val agent: AgentId?,
    public val kind: TurnKind,
    /** What the model reads as the turn's input. */
    public val prompt: String,
    public val trust: Trust,
    public val route: ReplyRoute,
    /**
     * The operator's leave that the turn runs as them under, null for a turn of nobody; it must be the initiating
     * plugin's, for the turn's agent and chat, and its operator still one there, else the turn runs as nobody.
     */
    public val mandate: Mandate?,
) {
    init {
        require(kind != TurnKind.MESSAGE && kind != TurnKind.COMMAND && kind != TurnKind.DELEGATED) {
            "An initiated turn cannot be of kind $kind: messages and commands come from channels, delegated turns " +
                "from a delegation."
        }
        require(prompt.isNotBlank()) { "The prompt of an initiated turn may not be blank." }
        require(chat.instance != ChatAddress.AGENTS || (kind == TurnKind.HEARTBEAT && agent?.value == chat.chat)) {
            "Only a heartbeat of its agent belongs to no chat, was $kind of ${agent ?: "the serving agent"} at $chat."
        }
    }

    public fun toBuilder(): Builder = Builder(chat, agent, kind, prompt)
        .trust(trust)
        .route(route)
        .mandate(mandate)

    public class Builder internal constructor(
        private val chat: ChatAddress,
        private val agent: AgentId?,
        private var kind: TurnKind,
        private var prompt: String,
    ) {
        private var trust: Trust = Trust.UNTRUSTED
        private var route: ReplyRoute = ReplyRoute.None
        private var mandate: Mandate? = null

        public fun kind(kind: TurnKind): Builder = apply { this.kind = kind }

        public fun prompt(prompt: String): Builder = apply { this.prompt = prompt }

        public fun trust(trust: Trust): Builder = apply { this.trust = trust }

        public fun route(route: ReplyRoute): Builder = apply { this.route = route }

        public fun mandate(mandate: Mandate?): Builder = apply { this.mandate = mandate }

        public fun build(): InitiatedTurn = InitiatedTurn(chat, agent, kind, prompt, trust, route, mandate)
    }

    public companion object {
        /** A turn at [chat], run as the agent that serves it. */
        public fun builder(chat: ChatAddress, kind: TurnKind, prompt: String): Builder =
            Builder(chat, null, kind, prompt)

        /** A turn at the chat of [key], run as its agent. */
        public fun builder(key: AgentChatKey, kind: TurnKind, prompt: String): Builder =
            Builder(key.chat, key.agent, kind, prompt)

        /** A heartbeat of [agent] that belongs to no chat, at [ChatAddress.ofAgent]; its reply goes nowhere. */
        public fun heartbeat(agent: AgentId, prompt: String): Builder =
            Builder(ChatAddress.ofAgent(agent), agent, TurnKind.HEARTBEAT, prompt)
    }
}

public inline fun InitiatedTurn.rebuild(block: InitiatedTurn.Builder.() -> Unit): InitiatedTurn =
    toBuilder().apply(block).build()

/**
 * Where the agent sends an initiated turn's final reply, through `Channel.send`, to the chats that are on instances the
 * turn's agent is attached to and take proactive messages.
 */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ReplyRoute {
    /** Nowhere: the reply stays in the transcript. */
    @OptIn(InternalAlexandriteApi::class)
    public data object None : ReplyRoute

    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class ToChat(public val chat: ChatAddress) : ReplyRoute

    /** The home chat of the turn's agent on each of [channels], or on each of the agent's channels when null. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class AgentHomes private constructor(public val channels: Set<ChannelInstanceId>?) : ReplyRoute {
        init {
            requireChannels(channels)
        }

        public companion object {
            public operator fun invoke(channels: Set<ChannelInstanceId>? = null): AgentHomes =
                AgentHomes(channels?.toSet())
        }
    }

    /** Every chat where the turn's agent has a conversation, on [channels], or on all its channels when null. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Broadcast private constructor(public val channels: Set<ChannelInstanceId>?) : ReplyRoute {
        init {
            requireChannels(channels)
        }

        public companion object {
            public operator fun invoke(channels: Set<ChannelInstanceId>? = null): Broadcast =
                Broadcast(channels?.toSet())
        }
    }
}

private fun requireChannels(channels: Set<ChannelInstanceId>?) {
    require(channels == null || channels.isNotEmpty()) { "A route names some channel instances, or null for all." }
}
