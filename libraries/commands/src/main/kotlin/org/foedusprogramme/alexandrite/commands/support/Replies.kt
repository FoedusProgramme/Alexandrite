package org.foedusprogramme.alexandrite.commands.support

import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import org.foedusprogramme.alexandrite.sdk.turn.AgentSource
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsSnapshot
import org.foedusprogramme.alexandrite.sdk.turn.CommandSpec

/** The plugin's texts in [language]. */
internal class Replies(private val texts: Texts, private val language: LanguageTag?) {
    fun text(key: String, vararg args: Pair<String, Any?>): String = texts.text(key, language, *args)

    fun usage(command: String): String = text(TextKeys.usage(command))

    /** Why the agent of [snapshot] serves [chat]. */
    fun agentSource(snapshot: ChatSettingsSnapshot, chat: ChatAddress): String = when (snapshot.agentSource) {
        AgentSource.SELECTED -> text(TextKeys.SOURCE_SELECTED)
        AgentSource.HOME -> text(TextKeys.SOURCE_HOME)
        AgentSource.DEFAULT -> text(TextKeys.SOURCE_INSTANCE_DEFAULT, "instance" to chat.instance)
        else -> text(TextKeys.SOURCE_OTHER, "source" to snapshot.agentSource)
    }

    fun model(snapshot: ChatSettingsSnapshot): Setting =
        setting(snapshot.model, snapshot.defaults.model, TextKeys.VALUE_NONE, TextKeys.SOURCE_NO_MODEL)

    fun reasoning(snapshot: ChatSettingsSnapshot): Setting =
        setting(snapshot.reasoning, snapshot.defaults.reasoning, TextKeys.VALUE_DEFAULT, TextKeys.SOURCE_MODEL_DEFAULT)

    fun language(snapshot: ChatSettingsSnapshot): Setting =
        setting(snapshot.language, snapshot.defaults.language, TextKeys.VALUE_DEFAULT, TextKeys.SOURCE_HOST_DEFAULT)

    /** [items] as the lines of a list. */
    fun list(items: List<Any>): String = items.joinToString("\n") { text(TextKeys.LIST_ITEM, "item" to it) }

    private fun setting(stored: Any?, configured: Any?, unset: String, unsetSource: String): Setting = when {
        stored != null -> Setting("$stored", text(TextKeys.SOURCE_CHAT))
        configured != null -> Setting("$configured", text(TextKeys.SOURCE_AGENT))
        else -> Setting(text(unset), text(unsetSource))
    }
}

/** A setting's value in force and why it is in force. */
internal class Setting(val value: String, val source: String)

internal fun Texts.replies(language: LanguageTag?): Replies = Replies(this, language)

/** The language a chat with these settings is answered in, null for the host's. */
internal val ChatSettingsSnapshot.languageInForce: LanguageTag? get() = language ?: defaults.language

/** The command [name], described by the English text of its description. */
internal fun Texts.spec(name: String): CommandSpec =
    CommandSpec.builder(name, text(TextKeys.description(name), ENGLISH)).build()

private val ENGLISH = LanguageTag("en")
