package org.unitedsoftware.alexandrite.agent.delivery

import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.control.SettingsStates
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.i18n.Texts

/** The texts of the agent's notices, in the language a chat gets from its agent. */
@Singleton
internal class Notices(
    private val texts: Texts,
    private val directory: AgentDirectory,
    private val settings: SettingsStates,
) {
    /** The text [key] in the language of [turn], else of its agent. */
    fun text(turn: TurnInfo, key: String, vararg args: Pair<String, Any?>): String =
        texts.text(key, turn.language ?: directory.agent(turn.agent)?.language, *args)

    /** The text [key] in the host's language. */
    fun text(key: String, vararg args: Pair<String, Any?>): String = texts.text(key, null, *args)

    /** The text [key] in the language of the chat at [chat]. */
    suspend fun text(chat: AgentChatKey, key: String, vararg args: Pair<String, Any?>): String =
        texts.text(key, settings.language(chat) ?: directory.agent(chat.agent)?.language, *args)
}

/** A notice that replies to [trigger]. */
internal fun notice(text: String, trigger: ChannelMessageRef?, conversation: ConversationId?): OutboundMessage =
    OutboundMessage.builder(text, MessageKind.NOTICE).replyTo(trigger).conversation(conversation).build()
