package org.unitedsoftware.alexandrite.automation.heartbeat

import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.AgentControl
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import org.unitedsoftware.alexandrite.sdk.turn.ControlRefusedException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration

/** `/heartbeat`: the heartbeat of the chat's agent. */
@Singleton
@Contribute(CommandHandler::class)
internal class HeartbeatCommand(
    private val heartbeats: Heartbeats,
    private val runner: HeartbeatRunner,
    private val control: AgentControl?,
    private val texts: Texts,
) : CommandHandler {
    override val commands: List<CommandSpec> =
        listOf(CommandSpec.builder(NAME, description(NAME, null)).build())

    override fun description(command: String, language: LanguageTag?): String =
        texts.text("command.$command.description", language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val replies = Replies(texts, context.turn.language)
        if (invocation.arguments.isNotBlank()) {
            context.reply(replies.text(TextKeys.USAGE))
            return
        }
        val control = control ?: return
        val agent = try {
            control.settings(invocation.chat).agent
        } catch (e: ControlRefusedException) {
            context.reply(e.message ?: throw e)
            return
        }
        val name = name(control, agent, invocation.chat)
        val heartbeat = heartbeats.of(agent)
        if (heartbeat == null) {
            context.reply(replies.text(TextKeys.NONE, "agent" to name))
            return
        }
        context.reply(replies.shown(heartbeat, name, runner.nextBeat(heartbeat), heartbeats.homeless(agent)))
    }

    private fun name(control: AgentControl, agent: AgentId, chat: ChatAddress): String =
        control.agents(chat.instance).firstOrNull { it.id == agent }?.name ?: agent.value

    private companion object {
        const val NAME = "heartbeat"
    }
}

/** The plugin's texts in [language]. */
private class Replies(private val texts: Texts, private val language: LanguageTag?) {
    fun text(key: String, vararg args: Pair<String, Any?>): String = texts.text(key, language, *args)

    /** [heartbeat] of the agent [name], next due at [next], with the warning for an agent that is [homeless]. */
    fun shown(heartbeat: Heartbeat, name: String, next: Instant?, homeless: Boolean): String {
        val hours = heartbeat.activeHours?.let {
            text(TextKeys.HOURS_BETWEEN, "start" to it.start.format(CLOCK), "end" to it.end.format(CLOCK))
        } ?: text(TextKeys.HOURS_ALL_DAY)
        val zone = if (heartbeat.zone == ZoneOffset.UTC) "UTC" else "${heartbeat.zone}"
        val lines = listOfNotNull(
            text(
                TextKeys.SHOWN,
                "agent" to name,
                "every" to duration(heartbeat.every),
                "hours" to hours,
                "zone" to zone,
            ),
            next?.let { text(TextKeys.NEXT, "time" to it.atZone(heartbeat.zone).format(DATE_TIME)) }
                ?: text(TextKeys.NEXT_NONE),
            text(TextKeys.NO_HOME, "agent" to name).takeIf { homeless },
        )
        return lines.joinToString("\n")
    }

    private fun duration(duration: Duration): String {
        val hours = duration.inWholeHours
        val minutes = duration.inWholeMinutes % 60
        return when {
            hours == 0L -> text(TextKeys.DURATION_MINUTES, "minutes" to minutes)
            minutes == 0L -> text(TextKeys.DURATION_HOURS, "hours" to hours)
            else -> text(TextKeys.DURATION_HOURS_MINUTES, "hours" to hours, "minutes" to minutes)
        }
    }
}

/** The keys of the plugin's texts for people. */
internal object TextKeys {
    const val USAGE: String = "usage.heartbeat"
    const val NONE: String = "heartbeat.none"
    const val SHOWN: String = "heartbeat.shown"
    const val HOURS_ALL_DAY: String = "heartbeat.hours_all_day"
    const val HOURS_BETWEEN: String = "heartbeat.hours_between"
    const val NEXT: String = "heartbeat.next"
    const val NEXT_NONE: String = "heartbeat.next_none"
    const val NO_HOME: String = "heartbeat.no_home"
    const val DURATION_MINUTES: String = "duration.minutes"
    const val DURATION_HOURS: String = "duration.hours"
    const val DURATION_HOURS_MINUTES: String = "duration.hours_minutes"
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
