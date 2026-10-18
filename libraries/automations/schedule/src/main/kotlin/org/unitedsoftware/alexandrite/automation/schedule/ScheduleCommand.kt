package org.unitedsoftware.alexandrite.automation.schedule

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.automation.common.When
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import java.time.Clock

/** `/schedule`: anyone lists the chat's tasks, an admin cancels one. */
@Singleton
@Contribute(CommandHandler::class)
internal class ScheduleCommand(control: Lazy<ScheduleControl>, private val texts: Texts, private val clock: Clock) :
    CommandHandler {
    private val control by control

    override val commands: List<CommandSpec> =
        listOf(CommandSpec.builder(SCHEDULE, texts.text(TextKeys.DESCRIPTION, null)).build())

    override fun description(command: String, language: LanguageTag?): String =
        texts.text(TextKeys.DESCRIPTION, language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val turn = context.turn
        val wording = Wording(texts, turn.language, turn.zone ?: clock.zone)
        val words = invocation.arguments.trim().split(WHITESPACE).filter(String::isNotEmpty)
        val reply = try {
            when {
                words.isEmpty() -> list(context, wording)

                words.size == 2 && words[0].equals(CANCEL, ignoreCase = true) -> when {
                    !invocation.issuer.isAdmin -> wording.text(TextKeys.REFUSED_ADMIN)
                    else -> cancel(words[1].lowercase(), invocation, context, wording)
                }

                else -> wording.text(TextKeys.USAGE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Cannot run /schedule in {}: {}", invocation.chat, e.toString())
            wording.text(TextKeys.UNAVAILABLE)
        }
        context.reply(reply)
    }

    private suspend fun list(context: CommandContext, wording: Wording): String {
        val tasks = control.list(context.turn.key)
        if (tasks.isEmpty()) return wording.text(TextKeys.LIST_NONE)
        val lines = tasks.map { task ->
            val repeat = when (val schedule = task.schedule) {
                is When.Once -> wording.text(TextKeys.WHEN_ONCE)

                is When.Every -> wording.text(TextKeys.WHEN_EVERY, "interval" to shown(schedule.interval))

                is When.Cron ->
                    wording.text(TextKeys.WHEN_CRON, "expression" to schedule.expression, "zone" to task.zone)
            }
            val next = when {
                task.running -> wording.text(TextKeys.LIST_RUNNING)
                else -> task.next?.let { shown(it, wording.zone) } ?: "-"
            }
            wording.text(
                TextKeys.LIST_ITEM,
                "id" to task.id,
                "when" to repeat,
                "next" to next,
                "user" to task.creatorName,
                "prompt" to brief(task.prompt),
            )
        }
        return wording.text(TextKeys.LIST, "tasks" to lines.joinToString("\n"))
    }

    private suspend fun cancel(
        id: String,
        invocation: CommandInvocation,
        context: CommandContext,
        wording: Wording,
    ): String {
        val cancelled = control.cancel(context.turn.key, id, invocation.issuer)
        return wording.text(if (cancelled) TextKeys.CANCELLED else TextKeys.CANCEL_UNKNOWN, "id" to id)
    }

    private companion object {
        const val SCHEDULE = "schedule"
        const val CANCEL = "cancel"
        val WHITESPACE = Regex("\\s+")
    }
}

private val logger = LoggerFactory.getLogger(ScheduleCommand::class.java)
