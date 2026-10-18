package org.unitedsoftware.alexandrite.agent.control

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress

/** Who made a change at runtime, as their channel saw them then. */
@Serializable
internal class Granter(
    val address: UserAddress,
    val name: String,
    val username: String? = null,
    val bot: Boolean = false,
    @SerialName("admin") val operator: Boolean = false,
) {
    constructor(user: ChatUser) : this(user.address, user.displayName, user.username, user.isBot, user.isOperator)

    fun user(): ChatUser = ChatUser(address, name, username, bot, operator)
}
