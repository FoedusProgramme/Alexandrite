package org.foedusprogramme.alexandrite.sdk.store

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.chat.TurnLineage
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import java.time.Instant

/** A turn as the store keeps it. */
@Poko
public class TurnRecord private constructor(
    public val id: TurnId,
    public val conversation: ConversationId,
    public val key: AgentChatKey,
    public val kind: TurnKind,
    /** The address of the turn's principal, null when it has none. */
    public val actor: UserAddress?,
    /** Null for a top-level turn. */
    public val lineage: TurnLineage?,
    public val startedAt: Instant,
    /** Null while the turn runs. */
    public val endedAt: Instant?,
    /** Null while the turn runs. */
    public val end: TurnEndKind?,
) {
    public fun toBuilder(): Builder = Builder(id, conversation, key, kind, startedAt)
        .actor(actor)
        .lineage(lineage)
        .endedAt(endedAt)
        .end(end)

    public class Builder internal constructor(
        private var id: TurnId,
        private var conversation: ConversationId,
        private var key: AgentChatKey,
        private var kind: TurnKind,
        private var startedAt: Instant,
    ) {
        private var actor: UserAddress? = null
        private var lineage: TurnLineage? = null
        private var endedAt: Instant? = null
        private var end: TurnEndKind? = null

        public fun id(id: TurnId): Builder = apply { this.id = id }

        public fun conversation(conversation: ConversationId): Builder = apply { this.conversation = conversation }

        public fun key(key: AgentChatKey): Builder = apply { this.key = key }

        public fun kind(kind: TurnKind): Builder = apply { this.kind = kind }

        public fun startedAt(startedAt: Instant): Builder = apply { this.startedAt = startedAt }

        public fun actor(actor: UserAddress?): Builder = apply { this.actor = actor }

        public fun lineage(lineage: TurnLineage?): Builder = apply { this.lineage = lineage }

        public fun endedAt(endedAt: Instant?): Builder = apply { this.endedAt = endedAt }

        public fun end(end: TurnEndKind?): Builder = apply { this.end = end }

        public fun build(): TurnRecord =
            TurnRecord(id, conversation, key, kind, actor, lineage, startedAt, endedAt, end)
    }

    public companion object {
        public fun builder(
            id: TurnId,
            conversation: ConversationId,
            key: AgentChatKey,
            kind: TurnKind,
            startedAt: Instant,
        ): Builder = Builder(id, conversation, key, kind, startedAt)
    }
}

public inline fun TurnRecord.rebuild(block: TurnRecord.Builder.() -> Unit): TurnRecord =
    toBuilder().apply(block).build()

/** How a turn ended, one value per kind of `TurnOutcome`. */
@JvmInline
@Serializable
public value class TurnEndKind internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        public val COMPLETED: TurnEndKind = TurnEndKind("completed")

        public val ABSORBED: TurnEndKind = TurnEndKind("absorbed")

        public val TAKEN_BACK: TurnEndKind = TurnEndKind("taken_back")

        public val CANCELLED: TurnEndKind = TurnEndKind("cancelled")

        public val FAILED: TurnEndKind = TurnEndKind("failed")

        public val SHUT_DOWN: TurnEndKind = TurnEndKind("shut_down")

        /** The values this version knows. */
        public val entries: List<TurnEndKind> =
            listOf(COMPLETED, ABSORBED, TAKEN_BACK, CANCELLED, FAILED, SHUT_DOWN)

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): TurnEndKind = TurnEndKind(id)
    }
}
