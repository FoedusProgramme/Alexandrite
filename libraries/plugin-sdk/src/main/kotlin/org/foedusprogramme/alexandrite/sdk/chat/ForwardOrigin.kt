package org.foedusprogramme.alexandrite.sdk.chat

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable

/** Where a forwarded message came from. */
@Poko
public class ForwardOrigin(
    public val kind: ForwardKind,
    /** The origin's name as the platform shows it. */
    public val name: String?,
    /** The original sender, null when hidden or not a user. */
    public val user: UserAddress?,
    /** The original chat, null when it is not one the channel can address. */
    public val chat: ChatAddress?,
)

/** What a forwarded message came from. */
@JvmInline
@Serializable
public value class ForwardKind internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** A user, perhaps hidden. */
        public val USER: ForwardKind = ForwardKind("user")

        /** A chat posting as itself. */
        public val CHAT: ForwardKind = ForwardKind("chat")

        /** An origin the channel cannot classify. */
        public val UNKNOWN: ForwardKind = ForwardKind("unknown")
    }
}
