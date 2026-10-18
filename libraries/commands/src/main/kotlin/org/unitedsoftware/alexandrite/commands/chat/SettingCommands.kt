package org.unitedsoftware.alexandrite.commands.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.commands.support.RESET
import org.unitedsoftware.alexandrite.commands.support.Replies
import org.unitedsoftware.alexandrite.commands.support.TextKeys
import org.unitedsoftware.alexandrite.commands.support.commandWord
import org.unitedsoftware.alexandrite.commands.support.description
import org.unitedsoftware.alexandrite.commands.support.groupOf
import org.unitedsoftware.alexandrite.commands.support.isKeyword
import org.unitedsoftware.alexandrite.commands.support.refusing
import org.unitedsoftware.alexandrite.commands.support.replies
import org.unitedsoftware.alexandrite.commands.support.shown
import org.unitedsoftware.alexandrite.commands.support.spec
import org.unitedsoftware.alexandrite.commands.support.updateSettings
import org.unitedsoftware.alexandrite.commands.support.words
import org.unitedsoftware.alexandrite.internal.time.zoneOrNull
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.turn.AgentControl
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/** `/model`, `/reasoning`, `/language`, `/timezone` and `/settings`. */
@Singleton
@Contribute(CommandHandler::class)
internal class SettingCommands(private val control: AgentControl, private val texts: Texts, private val clock: Clock) :
    CommandHandler {
    override val commands: List<CommandSpec> = listOf(MODEL, REASONING, LANGUAGE, TIMEZONE, SETTINGS).map(texts::spec)

    override fun description(command: String, language: LanguageTag?): String = texts.description(command, language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val replies = texts.replies(context.turn.language)
        val argument = invocation.arguments.trim()
        val words = words(argument)
        when (val name = commandWord(invocation.name)) {
            MODEL -> when {
                argument.isEmpty() -> model(invocation, context, replies)
                argument.isKeyword(RESET) -> resetModel(invocation, context)
                '/' in argument -> setModel(argument, invocation, context, replies)
                words.size == 1 -> listModels(argument, invocation, context, replies)
                else -> context.reply(replies.usage(name))
            }

            REASONING -> when {
                words.isEmpty() -> reasoning(invocation, context, replies)
                words.size > 1 -> context.reply(replies.usage(name))
                words[0].isKeyword(RESET) -> resetReasoning(invocation, context)
                else -> setReasoning(words[0], invocation, context, replies)
            }

            LANGUAGE -> when {
                words.isEmpty() -> language(invocation, context, replies)
                words.size > 1 -> context.reply(replies.usage(name))
                words[0].isKeyword(RESET) -> resetLanguage(invocation, context)
                else -> setLanguage(words[0], invocation, context, replies)
            }

            TIMEZONE -> when {
                words.isEmpty() -> zone(invocation, context, replies)
                words.size > 1 -> context.reply(replies.usage(name))
                words[0].isKeyword(RESET) -> resetZone(invocation, context)
                else -> setZone(words[0], invocation, context, replies)
            }

            SETTINGS -> when {
                words.isEmpty() -> settings(invocation, context, replies)
                else -> context.reply(replies.usage(name))
            }
        }
    }

    private suspend fun model(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val snapshot = context.refusing { control.settings(invocation.chat) } ?: return
        val model = replies.model(snapshot)
        val current = replies.text(TextKeys.MODEL_CURRENT, "model" to model.value, "source" to model.source)
        context.reply(current + "\n" + replies.text(TextKeys.MODEL_ENDPOINTS, "endpoints" to endpointIds(replies)))
    }

    private suspend fun listModels(
        endpointId: String,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val endpoint = control.endpoints().firstOrNull { it.value == endpointId }
        if (endpoint == null) {
            val text = replies.text(
                TextKeys.MODEL_NO_ENDPOINT,
                "endpoint" to endpointId,
                "endpoints" to endpointIds(replies),
            )
            context.reply(text)
            return
        }
        val snapshot = context.refusing { control.settings(invocation.chat) } ?: return
        val models = listing(endpoint)
        if (models == null) {
            context.reply(replies.text(TextKeys.MODEL_LIST_FAILED, "endpoint" to endpoint))
            return
        }
        val ids = models.map { it.id }.distinct().sorted()
        if (ids.isEmpty()) {
            context.reply(replies.text(TextKeys.MODEL_LIST_EMPTY, "endpoint" to endpoint))
            return
        }
        val current = (snapshot.model ?: snapshot.defaultModel)?.takeIf { it.endpoint == endpoint }?.model
        val shown = ids.take(MAX_LISTED).map { id ->
            if (id == current) "$id ${replies.text(TextKeys.MODEL_CURRENT_MARK)}" else id
        }
        val lines = listOfNotNull(
            replies.text(TextKeys.MODEL_LIST, "endpoint" to endpoint, "models" to replies.list(shown)),
            (ids.size - MAX_LISTED).takeIf { it > 0 }?.let { replies.text(TextKeys.MODEL_LIST_MORE, "count" to it) },
        )
        context.reply(lines.joinToString("\n"))
    }

    /** The models of [endpoint] as the agent lists them, null when it cannot list them in time. */
    private suspend fun listing(endpoint: EndpointId): List<ModelInfo>? {
        val models = try {
            withTimeoutOrNull(LISTING_TIMEOUT) { control.models(endpoint) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Cannot list the models of endpoint '{}': {}", endpoint, e.toString())
            return null
        }
        if (models == null) logger.warn("Endpoint '{}' did not list its models within {}", endpoint, LISTING_TIMEOUT)
        return models
    }

    private suspend fun setModel(
        text: String,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val model = ModelRef.parseOrNull(text)
        if (model == null) {
            context.reply(replies.usage(MODEL))
            return
        }
        val snapshot = context.updateSettings(control, invocation) { model(model) } ?: return
        context.reply(texts.replies(snapshot.effectiveLanguage).text(TextKeys.MODEL_SET, "model" to model))
    }

    private suspend fun resetModel(invocation: CommandInvocation, context: CommandContext) {
        val snapshot = context.updateSettings(control, invocation) { resetModel() } ?: return
        val replies = texts.replies(snapshot.effectiveLanguage)
        val model = replies.model(snapshot)
        context.reply(replies.text(TextKeys.MODEL_RESET, "model" to model.value, "source" to model.source))
    }

    private suspend fun reasoning(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val snapshot = context.refusing { control.settings(invocation.chat) } ?: return
        val effort = replies.reasoning(snapshot)
        val text = replies.text(
            TextKeys.REASONING_CURRENT,
            "effort" to effort.value,
            "source" to effort.source,
            "efforts" to EFFORTS,
        )
        context.reply(text)
    }

    private suspend fun setReasoning(
        text: String,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val effort = ReasoningEffort.of(text.lowercase(Locale.ROOT))
        if (effort !in ReasoningEffort.entries) {
            context.reply(replies.text(TextKeys.REASONING_UNKNOWN, "text" to text, "efforts" to EFFORTS))
            return
        }
        val snapshot = context.updateSettings(control, invocation) { reasoning(effort) } ?: return
        context.reply(texts.replies(snapshot.effectiveLanguage).text(TextKeys.REASONING_SET, "effort" to effort))
    }

    private suspend fun resetReasoning(invocation: CommandInvocation, context: CommandContext) {
        val snapshot = context.updateSettings(control, invocation) { resetReasoning() } ?: return
        val replies = texts.replies(snapshot.effectiveLanguage)
        val effort = replies.reasoning(snapshot)
        context.reply(replies.text(TextKeys.REASONING_RESET, "effort" to effort.value, "source" to effort.source))
    }

    private suspend fun language(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val snapshot = context.refusing { control.settings(invocation.chat) } ?: return
        val language = replies.language(snapshot)
        val text = replies.text(TextKeys.LANGUAGE_CURRENT, "language" to language.value, "source" to language.source)
        context.reply(text)
    }

    private suspend fun setLanguage(
        text: String,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val language = try {
            LanguageTag.of(text)
        } catch (e: IllegalArgumentException) {
            context.reply(replies.text(TextKeys.LANGUAGE_MALFORMED, "text" to text))
            return
        }
        val snapshot = context.updateSettings(control, invocation) { language(language) } ?: return
        context.reply(texts.replies(snapshot.effectiveLanguage).text(TextKeys.LANGUAGE_SET, "language" to language))
    }

    private suspend fun resetLanguage(invocation: CommandInvocation, context: CommandContext) {
        val snapshot = context.updateSettings(control, invocation) { resetLanguage() } ?: return
        val replies = texts.replies(snapshot.effectiveLanguage)
        val language = replies.language(snapshot)
        val text = replies.text(TextKeys.LANGUAGE_RESET, "language" to language.value, "source" to language.source)
        context.reply(text)
    }

    private suspend fun zone(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val snapshot = context.refusing { control.settings(invocation.chat) } ?: return
        val zone = replies.zone(snapshot, clock.zone)
        context.reply(
            replies.text(
                TextKeys.TIMEZONE_CURRENT,
                "zone" to zone.value,
                "source" to zone.source,
                "time" to now(snapshot.effectiveZone),
            ),
        )
    }

    private suspend fun setZone(
        text: String,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val zone = zoneOrNull(text)
        if (zone == null) {
            context.reply(replies.text(TextKeys.TIMEZONE_MALFORMED, "text" to text))
            return
        }
        val snapshot = context.updateSettings(control, invocation) { zone(zone) } ?: return
        val set = texts.replies(snapshot.effectiveLanguage)
        context.reply(set.text(TextKeys.TIMEZONE_SET, "zone" to shown(zone), "time" to now(zone)))
    }

    private suspend fun resetZone(invocation: CommandInvocation, context: CommandContext) {
        val snapshot = context.updateSettings(control, invocation) { resetZone() } ?: return
        val replies = texts.replies(snapshot.effectiveLanguage)
        val zone = replies.zone(snapshot, clock.zone)
        val text = replies.text(
            TextKeys.TIMEZONE_RESET,
            "zone" to zone.value,
            "source" to zone.source,
            "time" to now(snapshot.effectiveZone),
        )
        context.reply(text)
    }

    /** The time now in [zone], the host's when null. */
    private fun now(zone: ZoneId?): String = CLOCK_TIME.format(clock.instant().atZone(zone ?: clock.zone))

    private suspend fun settings(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val chat = invocation.chat
        val snapshot = context.refusing { control.settings(chat) } ?: return
        val agent = snapshot.agent
        val instance = chat.instance
        val summary = control.agents(instance).firstOrNull { it.id == agent }
        val group = control.groupOf(agent, chat)
        val model = replies.model(snapshot)
        val effort = replies.reasoning(snapshot)
        val language = replies.language(snapshot)
        val zone = replies.zone(snapshot, clock.zone)
        val mode = replies.mode(snapshot)
        val bypassLeft = replies.bypassLeft(snapshot, clock.instant())
        val home = summary?.home
        val lines = listOf(
            replies.text(TextKeys.SETTINGS_TITLE, "chat" to chat),
            replies.text(
                TextKeys.SETTINGS_AGENT,
                "agent" to agent,
                "name" to (summary?.name ?: agent),
                "source" to replies.agentSource(snapshot, chat),
            ),
            replies.text(TextKeys.SETTINGS_MODEL, "model" to model.value, "source" to model.source),
            replies.text(TextKeys.SETTINGS_REASONING, "effort" to effort.value, "source" to effort.source),
            replies.text(TextKeys.SETTINGS_LANGUAGE, "language" to language.value, "source" to language.source),
            replies.text(TextKeys.SETTINGS_TIMEZONE, "zone" to zone.value, "source" to zone.source),
            if (bypassLeft == null) {
                replies.text(TextKeys.SETTINGS_MODE, "mode" to mode.value, "source" to mode.source)
            } else {
                replies.text(TextKeys.SETTINGS_MODE_BYPASS, "source" to mode.source, "left" to bypassLeft)
            },
            when {
                snapshot.home -> replies.text(TextKeys.SETTINGS_HOME_HERE, "agent" to agent, "instance" to instance)

                home != null -> replies.text(
                    TextKeys.SETTINGS_HOME_ELSEWHERE,
                    "agent" to agent,
                    "instance" to instance,
                    "home" to home,
                )

                else -> replies.text(TextKeys.SETTINGS_HOME_NONE, "agent" to agent, "instance" to instance)
            },
            if (group == null) {
                replies.text(TextKeys.SETTINGS_NOT_LINKED, "agent" to agent)
            } else {
                val others = (listOf(group.anchor) + group.members).filter { it != chat }
                replies.text(
                    TextKeys.SETTINGS_LINKED,
                    "agent" to agent,
                    "chats" to others.joinToString(", "),
                    "anchor" to group.anchor,
                )
            },
            replies.text(if (invocation.issuer.isAdmin) TextKeys.SETTINGS_ADMIN else TextKeys.SETTINGS_MEMBER),
        )
        context.reply(lines.joinToString("\n"))
    }

    private fun endpointIds(replies: Replies): String =
        control.endpoints().joinToString(", ") { it.value }.ifEmpty { replies.text(TextKeys.VALUE_NONE) }

    private companion object {
        const val MODEL = "model"
        const val REASONING = "reasoning"
        const val LANGUAGE = "language"
        const val TIMEZONE = "timezone"
        const val SETTINGS = "settings"
        const val MAX_LISTED = 40
        val LISTING_TIMEOUT = 10.seconds
        val EFFORTS = ReasoningEffort.entries.joinToString(", ")
        val CLOCK_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm XXX", Locale.ROOT)
        val logger = LoggerFactory.getLogger(SettingCommands::class.java)
    }
}
