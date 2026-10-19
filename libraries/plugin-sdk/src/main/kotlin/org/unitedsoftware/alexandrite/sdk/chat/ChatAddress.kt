package org.unitedsoftware.alexandrite.sdk.chat

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.sdk.StringFormSerializer

/**
 * A chat, or a thread of one, printed `<channel>:<instance>:<chat>[#<thread>]` with `#` and `%` in the ids
 * percent-encoded.
 */
@Serializable(with = ChatAddressSerializer::class)
@Poko
public class ChatAddress(
    public val instance: ChannelInstanceId,
    /** Defined by the channel, stable and unique within its instance. */
    public val chat: String,
    /** An independent sub-stream of [chat], null for the chat itself. */
    public val thread: String? = null,
) {
    init {
        requireOpaqueId(chat, "chat")
        thread?.let { requireOpaqueId(it, "thread") }
    }

    /** The same chat without the thread. */
    public val parent: ChatAddress get() = if (thread == null) this else ChatAddress(instance, chat)

    override fun toString(): String {
        val head = "$instance:${encodeId(chat, RESERVED)}"
        return if (thread == null) head else "$head#${encodeId(thread, RESERVED)}"
    }

    public companion object {
        /** The instance of the agents' own chats, which no channel serves. */
        public val AGENTS: ChannelInstanceId = ChannelInstanceId(ChannelType("alexandrite"), "agent")

        /** The chat of [agent]'s turns that belong to no chat, such as its heartbeats. */
        public fun ofAgent(agent: AgentId): ChatAddress = ChatAddress(AGENTS, agent.value)

        public fun parse(text: String): ChatAddress =
            parseOrNull(text) ?: throw IllegalArgumentException("Malformed chat address '$text'.")

        public fun parseOrNull(text: String): ChatAddress? {
            val first = text.indexOf(':')
            val second = text.indexOf(':', first + 1)
            if (first < 0 || second < 0) return null
            val instance = ChannelInstanceId.parseOrNull(text.substring(0, second)) ?: return null
            val ids = text.substring(second + 1)
            val chat = decodeId(ids.substringBefore('#'), RESERVED)
            val thread = if ('#' in ids) decodeId(ids.substringAfter('#'), RESERVED) ?: return null else null
            if (chat == null || !isOpaqueId(chat) || (thread != null && !isOpaqueId(thread))) return null
            return ChatAddress(instance, chat, thread)
        }
    }
}

private const val RESERVED = "#"

internal object ChatAddressSerializer : StringFormSerializer<ChatAddress>(
    "org.unitedsoftware.alexandrite.sdk.chat.ChatAddress",
    "chat address",
    ChatAddress::parseOrNull,
)
