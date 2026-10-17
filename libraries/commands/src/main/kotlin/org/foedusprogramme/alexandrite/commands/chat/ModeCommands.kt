package org.foedusprogramme.alexandrite.commands.chat

import org.foedusprogramme.alexandrite.commands.support.RESET
import org.foedusprogramme.alexandrite.commands.support.Replies
import org.foedusprogramme.alexandrite.commands.support.TextKeys
import org.foedusprogramme.alexandrite.commands.support.description
import org.foedusprogramme.alexandrite.commands.support.isKeyword
import org.foedusprogramme.alexandrite.commands.support.refusing
import org.foedusprogramme.alexandrite.commands.support.replies
import org.foedusprogramme.alexandrite.commands.support.spec
import org.foedusprogramme.alexandrite.commands.support.updateSettings
import org.foedusprogramme.alexandrite.commands.support.words
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import org.foedusprogramme.alexandrite.sdk.permission.PermissionMode
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsSnapshot
import org.foedusprogramme.alexandrite.sdk.turn.CommandContext
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import org.foedusprogramme.alexandrite.sdk.turn.CommandSpec
import java.time.Clock
import java.time.Duration
import java.util.Locale

/** `/mode`. */
@Singleton
@Contribute(CommandHandler::class)
internal class ModeCommands(private val control: AgentControl, private val texts: Texts, private val clock: Clock) :
    CommandHandler {
    override val commands: List<CommandSpec> = listOf(texts.spec(MODE))

    override fun description(command: String, language: LanguageTag?): String = texts.description(command, language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val replies = texts.replies(context.turn.language)
        val words = words(invocation.arguments)
        when {
            words.isEmpty() -> current(invocation, context, replies)
            words.size > 2 || (words.size == 2 && words[0].isKeyword(RESET)) -> context.reply(replies.usage(MODE))
            words[0].isKeyword(RESET) -> reset(invocation, context)
            else -> set(words, invocation, context, replies)
        }
    }

    private suspend fun current(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val snapshot = context.refusing { control.settings(invocation.chat) } ?: return
        val mode = replies.mode(snapshot)
        val left = replies.bypassLeft(snapshot, clock.instant())
        val text = if (left == null) {
            replies.text(TextKeys.MODE_CURRENT, "mode" to mode.value, "source" to mode.source, "modes" to MODES)
        } else {
            replies.text(
                TextKeys.MODE_CURRENT_BYPASS,
                "source" to mode.source,
                "left" to left,
                "after" to after(snapshot),
                "modes" to MODES,
            )
        }
        context.reply(text)
    }

    private suspend fun set(
        words: List<String>,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val mode = PermissionMode.of(words[0].lowercase(Locale.ROOT))
        if (mode !in PermissionMode.entries) {
            context.reply(replies.text(TextKeys.MODE_UNKNOWN, "text" to words[0], "modes" to MODES))
            return
        }
        val given = words.getOrNull(1)
        if (given != null && mode != PermissionMode.BYPASS) {
            context.reply(replies.usage(MODE))
            return
        }
        val requested = given?.let(::duration)
        if (given != null && requested == null) {
            context.reply(replies.text(TextKeys.MODE_MALFORMED_DURATION, "text" to given))
            return
        }
        val now = clock.instant()
        val until = requested?.let { now + minOf(it, MAX_BYPASS) }
        val snapshot = context.updateSettings(control, invocation) { mode(mode, until) } ?: return
        val set = texts.replies(snapshot.effectiveLanguage)
        val left = set.bypassLeft(snapshot, now)
        if (left == null) {
            context.reply(set.text(TextKeys.MODE_SET, "mode" to mode))
            return
        }
        val lines = listOfNotNull(
            set.text(TextKeys.MODE_SET_BYPASS, "left" to left, "after" to after(snapshot)),
            set.text(TextKeys.MODE_BYPASS_CAPPED, "longest" to set.duration(MAX_BYPASS))
                .takeIf { requested != null && requested > MAX_BYPASS },
        )
        context.reply(lines.joinToString("\n"))
    }

    private suspend fun reset(invocation: CommandInvocation, context: CommandContext) {
        val snapshot = context.updateSettings(control, invocation) { resetMode() } ?: return
        val replies = texts.replies(snapshot.effectiveLanguage)
        val mode = replies.mode(snapshot)
        context.reply(replies.text(TextKeys.MODE_RESET, "mode" to mode.value, "source" to mode.source))
    }

    private companion object {
        const val MODE = "mode"
        val MAX_BYPASS: Duration = Duration.ofHours(24)
        val MODES = PermissionMode.entries.joinToString(", ")
        val DURATION = Regex("([1-9][0-9]{0,5})([mhd])", RegexOption.IGNORE_CASE)

        /** The mode that applies once the bypass of [snapshot] ends. */
        fun after(snapshot: ChatSettingsSnapshot): PermissionMode = snapshot.defaultMode ?: PermissionMode.ASK

        /** [text] such as `30m`, `2h` or `1d` as a duration, null when it is none. */
        fun duration(text: String): Duration? {
            val (amount, unit) = DURATION.matchEntire(text)?.destructured ?: return null
            val count = amount.toLong()
            return when (unit.lowercase(Locale.ROOT)) {
                "m" -> Duration.ofMinutes(count)
                "h" -> Duration.ofHours(count)
                else -> Duration.ofDays(count)
            }
        }
    }
}
