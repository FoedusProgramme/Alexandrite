package org.foedusprogramme.alexandrite.sdk.turn

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.di.PluginLocal
import org.foedusprogramme.alexandrite.sdk.model.Trust

/** Starts turns that are not user input in the plugin's name, which carry no user's authority. */
@PluginLocal
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface TurnInitiator {
    /** Queues [turn] with the queue, capacity and outcome rules of a submission, and returns at once. */
    public fun initiate(turn: InitiatedTurn): Admission
}

/** The agent's side of every plugin's [TurnInitiator]. */
@InternalAlexandriteApi
public interface TurnInitiation {
    /** Queues [turn] for [plugin], whose id its origin records. */
    public fun initiate(plugin: String, turn: InitiatedTurn): Admission
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
) {
    init {
        require(kind != TurnKind.MESSAGE && kind != TurnKind.COMMAND && kind != TurnKind.DELEGATED) {
            "An initiated turn cannot be of kind $kind: messages and commands come from channels, delegated turns " +
                "from a delegation."
        }
        require(prompt.isNotBlank()) { "The prompt of an initiated turn may not be blank." }
    }

    public fun toBuilder(): Builder = Builder(chat, agent, kind, prompt)
        .trust(trust)
        .route(route)

    public class Builder internal constructor(
        private var chat: ChatAddress,
        private var agent: AgentId?,
        private var kind: TurnKind,
        private var prompt: String,
    ) {
        private var trust: Trust = Trust.UNTRUSTED
        private var route: ReplyRoute = ReplyRoute.None

        public fun chat(chat: ChatAddress): Builder = apply { this.chat = chat }

        public fun agent(agent: AgentId?): Builder = apply { this.agent = agent }

        public fun kind(kind: TurnKind): Builder = apply { this.kind = kind }

        public fun prompt(prompt: String): Builder = apply { this.prompt = prompt }

        public fun trust(trust: Trust): Builder = apply { this.trust = trust }

        public fun route(route: ReplyRoute): Builder = apply { this.route = route }

        public fun build(): InitiatedTurn = InitiatedTurn(chat, agent, kind, prompt, trust, route)
    }

    public companion object {
        /** A turn at [chat], run as the agent that serves it. */
        public fun builder(chat: ChatAddress, kind: TurnKind, prompt: String): Builder =
            Builder(chat, null, kind, prompt)

        /** A turn at the chat of [key], run as its agent. */
        public fun builder(key: AgentChatKey, kind: TurnKind, prompt: String): Builder =
            Builder(key.chat, key.agent, kind, prompt)
    }
}

public inline fun InitiatedTurn.rebuild(block: InitiatedTurn.Builder.() -> Unit): InitiatedTurn =
    toBuilder().apply(block).build()

/** Where the agent sends an initiated turn's final reply, through `Channel.send`. */
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
    public class AgentHomes(public val channels: Set<ChannelInstanceId>? = null) : ReplyRoute {
        init {
            requireChannels(channels)
        }
    }

    /** Every chat where the turn's agent has a conversation, on [channels], or on all its channels when null. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Broadcast(public val channels: Set<ChannelInstanceId>? = null) : ReplyRoute {
        init {
            requireChannels(channels)
        }
    }
}

private fun requireChannels(channels: Set<ChannelInstanceId>?) {
    require(channels == null || channels.isNotEmpty()) { "A route names some channel instances, or null for all." }
}
