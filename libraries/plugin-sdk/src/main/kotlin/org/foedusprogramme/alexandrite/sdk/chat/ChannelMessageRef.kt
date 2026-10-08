package org.foedusprogramme.alexandrite.sdk.chat

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable

/** A message or an interaction in a chat. */
@Serializable
@Poko
public class ChannelMessageRef(
    public val chat: ChatAddress,
    /** Defined by the channel, the same when the platform delivers the message again. */
    public val id: String,
) {
    init {
        requireOpaqueId(id, "message")
    }
}
