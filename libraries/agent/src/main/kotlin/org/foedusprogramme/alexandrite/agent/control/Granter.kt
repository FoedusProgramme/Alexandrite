package org.foedusprogramme.alexandrite.agent.control

import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress

/** Who made a change at runtime, as their channel saw them then. */
@Serializable
internal class Granter(
    val address: UserAddress,
    val name: String,
    val username: String? = null,
    val bot: Boolean = false,
    val admin: Boolean = false,
) {
    constructor(user: ChatUser) : this(user.address, user.displayName, user.username, user.isBot, user.isAdmin)

    fun user(): ChatUser = ChatUser(address, name, username, bot, admin)
}
