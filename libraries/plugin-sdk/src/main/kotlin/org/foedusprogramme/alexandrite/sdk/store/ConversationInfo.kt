package org.foedusprogramme.alexandrite.sdk.store

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.TurnLineage
import java.time.Instant

/** A conversation as the store keeps it. */
@Poko
public class ConversationInfo private constructor(
    public val id: ConversationId,
    public val kind: ConversationKind,
    public val key: AgentChatKey,
    public val state: ConversationState,
    public val createdAt: Instant,
    /** Null while the conversation is not sealed. */
    public val sealedAt: Instant?,
    /** The conversation that replaced this one, null while it is not sealed. */
    public val successor: ConversationId?,
    /** Null for a conversation of a top-level turn. */
    public val lineage: TurnLineage?,
    /** Null when the conversation starts with a history of its own. */
    public val fork: ForkPoint?,
) {
    public fun toBuilder(): Builder = Builder(id, kind, key, state, createdAt)
        .sealedAt(sealedAt)
        .successor(successor)
        .lineage(lineage)
        .fork(fork)

    public class Builder internal constructor(
        private var id: ConversationId,
        private var kind: ConversationKind,
        private var key: AgentChatKey,
        private var state: ConversationState,
        private var createdAt: Instant,
    ) {
        private var sealedAt: Instant? = null
        private var successor: ConversationId? = null
        private var lineage: TurnLineage? = null
        private var fork: ForkPoint? = null

        public fun id(id: ConversationId): Builder = apply { this.id = id }

        public fun kind(kind: ConversationKind): Builder = apply { this.kind = kind }

        public fun key(key: AgentChatKey): Builder = apply { this.key = key }

        public fun state(state: ConversationState): Builder = apply { this.state = state }

        public fun createdAt(createdAt: Instant): Builder = apply { this.createdAt = createdAt }

        public fun sealedAt(sealedAt: Instant?): Builder = apply { this.sealedAt = sealedAt }

        public fun successor(successor: ConversationId?): Builder = apply { this.successor = successor }

        public fun lineage(lineage: TurnLineage?): Builder = apply { this.lineage = lineage }

        public fun fork(fork: ForkPoint?): Builder = apply { this.fork = fork }

        public fun build(): ConversationInfo =
            ConversationInfo(id, kind, key, state, createdAt, sealedAt, successor, lineage, fork)
    }

    public companion object {
        public fun builder(
            id: ConversationId,
            kind: ConversationKind,
            key: AgentChatKey,
            state: ConversationState,
            createdAt: Instant,
        ): Builder = Builder(id, kind, key, state, createdAt)
    }
}

public inline fun ConversationInfo.rebuild(block: ConversationInfo.Builder.() -> Unit): ConversationInfo =
    toBuilder().apply(block).build()

/** What a conversation is for. */
@JvmInline
@Serializable
public value class ConversationKind internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** The conversation that `/new` replaces. */
        public val USER_LANE: ConversationKind = ConversationKind("user_lane")

        /** The conversation of heartbeat turns, which `/new` keeps. */
        public val HEARTBEAT_BASE: ConversationKind = ConversationKind("heartbeat_base")

        /** The conversation of a sub-agent run. */
        public val DELEGATED: ConversationKind = ConversationKind("delegated")

        /** The values this version knows. */
        public val entries: List<ConversationKind> = listOf(USER_LANE, HEARTBEAT_BASE, DELEGATED)

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): ConversationKind = ConversationKind(id)
    }
}

/** The state of a conversation, of which only [ACTIVE] takes new entries. */
@JvmInline
@Serializable
public value class ConversationState internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        public val ACTIVE: ConversationState = ConversationState("active")

        /** Replaced by its successor. */
        public val SEALED: ConversationState = ConversationState("sealed")

        /** The values this version knows. */
        public val entries: List<ConversationState> = listOf(ACTIVE, SEALED)

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): ConversationState = ConversationState(id)
    }
}
