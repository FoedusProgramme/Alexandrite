package org.foedusprogramme.alexandrite.agent.delivery

import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
import org.foedusprogramme.alexandrite.agent.control.SettingsStates
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.i18n.Texts

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

    /** The text [key] in the language of the chat at [chat]. */
    suspend fun text(chat: AgentChatKey, key: String, vararg args: Pair<String, Any?>): String =
        texts.text(key, settings.language(chat) ?: directory.agent(chat.agent)?.language, *args)
}

/** A notice that replies to [trigger]. */
internal fun notice(text: String, trigger: ChannelMessageRef?, conversation: ConversationId?): OutboundMessage =
    OutboundMessage.builder(text, MessageKind.NOTICE).replyTo(trigger).conversation(conversation).build()
