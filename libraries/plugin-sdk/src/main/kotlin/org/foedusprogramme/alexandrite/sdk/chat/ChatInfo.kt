package org.foedusprogramme.alexandrite.sdk.chat

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable

/** What kind of chat a chat is. */
@JvmInline
@Serializable
public value class ChatKind internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** A chat that the bot and exactly one human can read, as the channel checked. */
        public val DIRECT: ChatKind = ChatKind("direct")

        /** A chat of several members. */
        public val GROUP: ChatKind = ChatKind("group")

        /** A chat that only its admins post to. */
        public val BROADCAST: ChatKind = ChatKind("broadcast")

        /** A chat the channel cannot classify. */
        public val UNKNOWN: ChatKind = ChatKind("unknown")

        /** The values this version knows. */
        public val entries: List<ChatKind> = listOf(DIRECT, GROUP, BROADCAST, UNKNOWN)

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): ChatKind = ChatKind(id)
    }
}

/** A chat as its channel last saw it. */
@Poko
public class ChatInfo(
    public val kind: ChatKind,
    public val title: String?,
    /** The chat's public handle, without a leading `@`. */
    public val username: String?,
)
