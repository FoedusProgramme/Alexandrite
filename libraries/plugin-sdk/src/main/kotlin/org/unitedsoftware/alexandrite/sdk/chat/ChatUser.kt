package org.unitedsoftware.alexandrite.sdk.chat

import dev.drewhamilton.poko.Poko

/** A user as their channel saw them. */
@Poko
public class ChatUser(
    public val address: UserAddress,
    public val displayName: String,
    /** The user's handle, without a leading `@`. */
    public val username: String?,
    public val isBot: Boolean,
    /** Whether the channel vouches that the user may act on the host on its behalf. */
    public val isOperator: Boolean,
    /** Whether the channel's config trusts what the user writes, so that their own messages do not taint. */
    public val isTrusted: Boolean = false,
)
