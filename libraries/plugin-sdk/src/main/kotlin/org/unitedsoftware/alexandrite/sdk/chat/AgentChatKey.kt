package org.unitedsoftware.alexandrite.sdk.chat

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.sdk.StringFormSerializer

/** A chat as one agent has it, printed `<agent>@<chat address>`. */
@Serializable(with = AgentChatKeySerializer::class)
@Poko
public class AgentChatKey(public val agent: AgentId, public val chat: ChatAddress) {
    /** The same agent at the chat without the thread. */
    public val parent: AgentChatKey get() = if (chat.thread == null) this else AgentChatKey(agent, chat.parent)

    override fun toString(): String = "$agent@$chat"

    public companion object {
        public fun parse(text: String): AgentChatKey =
            parseOrNull(text) ?: throw IllegalArgumentException("Malformed agent chat key '$text'.")

        public fun parseOrNull(text: String): AgentChatKey? {
            val agent = text.substringBefore('@', "").takeIf(::isId) ?: return null
            val chat = ChatAddress.parseOrNull(text.substringAfter('@')) ?: return null
            return AgentChatKey(AgentId(agent), chat)
        }
    }
}

internal object AgentChatKeySerializer : StringFormSerializer<AgentChatKey>(
    "org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey",
    "agent chat key",
    AgentChatKey::parseOrNull,
)
