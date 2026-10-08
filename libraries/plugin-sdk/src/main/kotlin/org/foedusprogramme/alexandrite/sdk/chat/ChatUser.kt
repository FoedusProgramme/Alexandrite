package org.foedusprogramme.alexandrite.sdk.chat

import dev.drewhamilton.poko.Poko

/** A user as their channel saw them. */
@Poko
public class ChatUser(
    public val address: UserAddress,
    public val displayName: String,
    /** The user's handle, without a leading `@`. */
    public val username: String?,
    public val isBot: Boolean,
    /** Whether the user is an operator of this agent per the config of the channel instance. */
    public val isAdmin: Boolean,
)
