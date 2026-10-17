package org.foedusprogramme.alexandrite.commands.support

import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import org.foedusprogramme.alexandrite.sdk.permission.PermissionMode
import org.foedusprogramme.alexandrite.sdk.turn.AgentSource
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsSnapshot
import org.foedusprogramme.alexandrite.sdk.turn.CommandSpec
import java.time.Duration
import java.time.Instant

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
        setting(snapshot.model, snapshot.defaultModel, TextKeys.VALUE_NONE, TextKeys.SOURCE_NO_MODEL)

    fun reasoning(snapshot: ChatSettingsSnapshot): Setting =
        setting(snapshot.reasoning, snapshot.defaultReasoning, TextKeys.VALUE_DEFAULT, TextKeys.SOURCE_MODEL_DEFAULT)

    fun language(snapshot: ChatSettingsSnapshot): Setting =
        setting(snapshot.language, snapshot.defaultLanguage, TextKeys.VALUE_DEFAULT, TextKeys.SOURCE_HOST_DEFAULT)

    fun mode(snapshot: ChatSettingsSnapshot): Setting = when {
        snapshot.mode != null -> Setting("${snapshot.mode}", text(TextKeys.SOURCE_CHAT))
        snapshot.defaultMode != null -> Setting("${snapshot.defaultMode}", text(TextKeys.SOURCE_AGENT))
        else -> Setting("${PermissionMode.ASK}", text(TextKeys.SOURCE_DEFAULT))
    }

    /** How long the chat's bypass of [snapshot] lasts after [now], null when the chat is in no bypass. */
    fun bypassLeft(snapshot: ChatSettingsSnapshot, now: Instant): String? {
        if (snapshot.mode != PermissionMode.BYPASS) return null
        return snapshot.modeUntil?.let { duration(Duration.between(now, it)) }
    }

    /** [duration] in whole minutes, rounded up. */
    fun duration(duration: Duration): String {
        val minutes = (duration.seconds + 59).coerceAtLeast(60) / 60
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0L -> text(TextKeys.DURATION_MINUTES, "minutes" to rest)
            rest == 0L -> text(TextKeys.DURATION_HOURS, "hours" to hours)
            else -> text(TextKeys.DURATION_HOURS_MINUTES, "hours" to hours, "minutes" to rest)
        }
    }

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

/** The command [name], described in the host's language. */
internal fun Texts.spec(name: String): CommandSpec = CommandSpec.builder(name, description(name, null)).build()

internal fun Texts.description(command: String, language: LanguageTag?): String =
    text(TextKeys.description(command), language)
