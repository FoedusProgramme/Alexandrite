package org.unitedsoftware.alexandrite.channel.telegram.approval

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.telegram.TelegramInstanceConfig
import org.unitedsoftware.alexandrite.channel.telegram.api.AnswerCallbackQuery
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApi
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiException
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiFailure
import org.unitedsoftware.alexandrite.channel.telegram.api.CallbackQuery
import org.unitedsoftware.alexandrite.channel.telegram.api.EditMessageReplyMarkup
import org.unitedsoftware.alexandrite.channel.telegram.api.InlineKeyboardButton
import org.unitedsoftware.alexandrite.channel.telegram.api.InlineKeyboardMarkup
import org.unitedsoftware.alexandrite.channel.telegram.format.FormattedText
import org.unitedsoftware.alexandrite.channel.telegram.format.MAX_MESSAGE_LENGTH
import org.unitedsoftware.alexandrite.channel.telegram.format.Span
import org.unitedsoftware.alexandrite.channel.telegram.i18n.TextKeys
import org.unitedsoftware.alexandrite.channel.telegram.intake.AccessGate
import org.unitedsoftware.alexandrite.channel.telegram.intake.ChatTitles
import org.unitedsoftware.alexandrite.channel.telegram.reply.Outbox
import org.unitedsoftware.alexandrite.channel.telegram.reply.SendLimiter
import org.unitedsoftware.alexandrite.channel.telegram.reply.Target
import org.unitedsoftware.alexandrite.channel.telegram.reply.targetOf
import org.unitedsoftware.alexandrite.internal.text.ReviewText
import org.unitedsoftware.alexandrite.internal.text.cutStartWithEllipsis
import org.unitedsoftware.alexandrite.internal.text.cutWithEllipsis
import org.unitedsoftware.alexandrite.internal.text.fittingLength
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalRequest
import org.unitedsoftware.alexandrite.sdk.channel.ChannelInstance
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatSettings
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes

/**
 * Shows approval requests as messages with buttons, in the chat of an admin's own turn and else in the admins' direct
 * chats, and takes the first answer an admin gives within the configured time.
 */
@ChannelInstanceScoped
internal class ApprovalDesk(
    private val instance: ChannelInstance,
    private val config: TelegramInstanceConfig,
    private val gate: AccessGate,
    private val outbox: Outbox,
    private val limiter: SendLimiter,
    private val api: BotApi,
    private val texts: Texts,
    private val settings: ChatSettings?,
    private val titles: ChatTitles,
    private val messages: ApprovalMessages,
) : Lifecycle {
    private val pending = ConcurrentHashMap<String, Pending>()
    private val work = SupervisorJob(instance.scope.coroutineContext[Job])
    private val scope = CoroutineScope(instance.scope.coroutineContext + work)

    /** The answer of the first admin who answers [request], else how the request ended. */
    suspend fun ask(request: ApprovalRequest): ApprovalAnswer {
        val chats = recipients(request)
        if (chats.isEmpty()) return UNREACHABLE
        val shown = chats.map { chat ->
            val language = if (chat == request.turn.chat) request.turn.language else language(chat)
            Shown(chat, language, render(request, language) ?: return TOO_LARGE)
        }
        val waiting = Pending(request)
        if (pending.putIfAbsent(request.id, waiting) != null) {
            return ApprovalAnswer.Unavailable("another request has the id ${request.id}")
        }
        try {
            for (each in shown) post(waiting, each)
            if (waiting.posted.isEmpty()) return UNREACHABLE
            logger.info(
                "{} asks for approval {} of {} in turn {} in {} chat(s)",
                instance.id,
                request.id,
                request.tool,
                request.turn.id,
                waiting.posted.size,
            )
            withTimeoutOrNull(config.approvalMinutes.minutes) { waiting.ending.await() }
            waiting.ending.complete(Ending.Expired)
            val ending = waiting.ending.await()
            conclude(waiting, ending)
            return ending.answer
        } catch (e: CancellationException) {
            waiting.ending.complete(Ending.Withdrawn)
            conclude(waiting, Ending.Withdrawn)
            throw e
        } finally {
            pending.remove(request.id)
        }
    }

    /** Takes the press [query] of [presser], answering it with a toast where it changes nothing. */
    fun press(query: CallbackQuery, presser: ChatUser) {
        scope.launch { answer(query, presser) }
    }

    override suspend fun onDrain() {
        work.children.toList().joinAll()
    }

    override fun onStop() {
        pending.values.forEach { it.ending.complete(Ending.Answered(ApprovalAnswer.Unavailable("the bot stopped"))) }
    }

    /** The chats [request] is shown in: the chat of an admin's own turn, else the admins' direct chats. */
    private fun recipients(request: ApprovalRequest): List<ChatAddress> {
        val turn = request.turn
        val chats = if (turn.actorIsAdmin) {
            listOf(turn.chat)
        } else {
            config.adminUserIds.distinct().map { ChatAddress(instance.id, "$it") }
        }
        return chats.filter(gate::isPaired)
    }

    /** [request] as the messages that show it in [language], null when it takes more than [MAX_PARTS]. */
    private fun render(request: ApprovalRequest, language: LanguageTag?): List<FormattedText>? {
        val turn = request.turn
        val title = text(TextKeys.APPROVAL_TITLE, language, "id" to request.id)
        val requester = turn.actor?.let(::named) ?: text(TextKeys.APPROVAL_NOBODY, language)
        val chat = named(turn.chat)
        val agents = request.chain.ifEmpty { listOf(turn.agent) }.joinToString(" → ")
        val lines = mutableListOf(
            text(TextKeys.APPROVAL_AGENT, language, "agent" to agents),
            text(TextKeys.APPROVAL_CHAT, language, "chat" to chat),
            text(TextKeys.APPROVAL_REQUESTER, language, "requester" to requester),
            text(TextKeys.APPROVAL_REASON, language, "reason" to request.reason),
            text(TextKeys.APPROVAL_TOOL, language, "tool" to request.tool, "risk" to request.risk.name.lowercase()),
        )
        val (introduction, body) = preview(request, language)
        lines += introduction
        val head = "$title\n${lines.joinToString("\n")}" + if (body.isBlank()) "" else "\n\n"
        return split(head, title.length, body)
    }

    /** The lines that introduce what [request] shows as code, and that code: its preview, else its arguments. */
    private fun preview(request: ApprovalRequest, language: LanguageTag?): Pair<List<String>, String> {
        val shown = when (val preview = request.preview) {
            is ReviewPreview.Diff -> listOf(text(TextKeys.APPROVAL_FILE, language, "path" to preview.path)) to
                preview.unified

            is ReviewPreview.Command -> {
                val program = preview.executable ?: text(TextKeys.APPROVAL_PROGRAM_UNKNOWN, language)
                listOfNotNull(
                    text(TextKeys.APPROVAL_DIRECTORY, language, "directory" to preview.cwd),
                    text(TextKeys.APPROVAL_PROGRAM, language, "program" to program),
                    text(TextKeys.APPROVAL_OPAQUE, language).takeIf { preview.opaque },
                ) to commandLine(preview.argv)
            }

            is ReviewPreview.Text -> emptyList<String>() to preview.text

            is ReviewPreview.OutgoingFile -> return listOfNotNull(
                text(TextKeys.APPROVAL_FILE, language, "path" to preview.path),
                text(TextKeys.APPROVAL_SIZE, language, "bytes" to preview.size, "readable" to readable(preview.size)),
                text(TextKeys.APPROVAL_SHA256, language, "sha256" to preview.sha256),
                text(TextKeys.APPROVAL_DESTINATION, language, "chat" to named(preview.destination)),
                text(TextKeys.APPROVAL_CAPTION, language).takeIf { preview.caption != null },
            ) to preview.caption.orEmpty()

            else -> null
        }
        if (shown != null && shown.second.isNotBlank()) return shown
        return listOf(text(TextKeys.APPROVAL_ARGUMENTS, language)) to request.arguments
    }

    /** [chat] as an approver sees it: its title where the channel knows one, and its address. */
    private fun named(chat: ChatAddress): String =
        titles.title(chat)?.let { "${ReviewText.escape(it)} ($chat)" } ?: "$chat"

    /** Sends [shown] to its chat, recording and noting each part as it is sent, undone if a later part fails. */
    private suspend fun post(waiting: Pending, shown: Shown) {
        val request = waiting.request
        val target = targetOf(shown.chat) ?: return
        val keyboard = keyboard(request, shown.language)
        val sent = mutableListOf<Posted>()
        try {
            for ((index, part) in shown.parts.withIndex()) {
                val last = index == shown.parts.lastIndex
                val id = outbox.paced(target) { outbox.sendText(target, part, null, keyboard.takeIf { last }) }
                messages.note(target.chat, id, waiting.noted)
                val posted = Posted(shown.chat, target, id, part, shown.language)
                waiting.posted += posted
                sent += posted
            }
        } catch (e: BotApiException) {
            outbox.checkToken(e)
            logger.warn("{} could not show approval {} in chat {}: {}", instance.id, request.id, shown.chat, e.message)
            sent.forEach {
                waiting.posted.remove(it)
                delete(it)
            }
        }
    }

    /** Deletes the message [posted] showed, so no part without buttons is left in the chat. */
    private suspend fun delete(posted: Posted) {
        try {
            outbox.paced(posted.target) { api.deleteMessage(posted.target.chat, posted.id) }
        } catch (e: BotApiException) {
            outbox.checkToken(e)
            logger.warn("{} could not delete an approval message in chat {}: {}", instance.id, posted.chat, e.message)
        }
    }

    private fun keyboard(request: ApprovalRequest, language: LanguageTag?): InlineKeyboardMarkup {
        val allow = InlineKeyboardButton(text(TextKeys.APPROVAL_ALLOW, language), "$PREFIX${request.id}:$ALLOW")
        val deny = InlineKeyboardButton(text(TextKeys.APPROVAL_DENY, language), "$PREFIX${request.id}:$DENY")
        val always = request.always?.let {
            val label = text(TextKeys.APPROVAL_ALWAYS, language, "rule" to ending(it.display))
            InlineKeyboardButton(label, "$PREFIX${request.id}:$ALWAYS")
        }
        return InlineKeyboardMarkup(listOfNotNull(listOf(allow, deny), always?.let(::listOf)))
    }

    private suspend fun answer(query: CallbackQuery, presser: ChatUser) {
        val press = query.data?.let(PRESS::matchEntire)?.destructured
        val chat = query.message?.let { ChatAddress(instance.id, "${it.chat.id}") }
        val language = chat?.let { language(it) }
        val toast = when {
            press == null -> null

            !presser.isAdmin -> {
                logger.info("{}: {}, who is no admin, pressed a button of an approval", instance.id, presser.address)
                text(TextKeys.APPROVAL_ADMINS_ONLY, language)
            }

            else -> {
                val (id, action) = press
                val waiting = pending[id]
                val always = action == ALWAYS && waiting?.request?.always != null
                val answer = if (action ==
                    DENY
                ) {
                    ApprovalAnswer.Denied(presser)
                } else {
                    ApprovalAnswer.Allowed(presser, always)
                }
                if (waiting?.ending?.complete(Ending.Answered(answer)) == true) {
                    null
                } else {
                    text(TextKeys.APPROVAL_NOT_PENDING, language)
                }
            }
        }
        try {
            limiter.message(null) { api.answerCallbackQuery(AnswerCallbackQuery(query.id, toast)) }
        } catch (e: BotApiException) {
            outbox.checkToken(e)
            logger.debug("{} could not answer a button press: {}", instance.id, e.message)
        }
    }

    /** Shows how [waiting] ended under each of its messages and takes their buttons away. */
    private fun conclude(waiting: Pending, ending: Ending) {
        waiting.noted.state = ending.state
        val posted = waiting.posted.toList()
        if (posted.isEmpty()) return
        val request = waiting.request
        logger.info("{}: approval {} of turn {} {}", instance.id, request.id, request.turn.id, ending.logged)
        scope.launch {
            for (message in posted) {
                val line = outcome(request, ending, message.language) ?: continue
                val shown = message.text
                edit(message, FormattedText("${shown.text}\n\n$line", shown.entities))
            }
        }
    }

    private suspend fun edit(message: Posted, text: FormattedText) {
        try {
            outbox.paced(message.target) { outbox.editText(message.target, message.id, text) }
        } catch (e: BotApiException) {
            if (e.failure == BotApiFailure.NOT_MODIFIED) return
            outbox.checkToken(e)
            logger.warn(
                "{} could not show the outcome of an approval in chat {}: {}",
                instance.id,
                message.chat,
                e.message,
            )
            try {
                outbox.paced(message.target) {
                    api.editMessageReplyMarkup(EditMessageReplyMarkup(message.target.chat, message.id))
                }
            } catch (e: BotApiException) {
                logger.warn("{} could not take the buttons away in chat {}: {}", instance.id, message.chat, e.message)
            }
        }
    }

    /** The line that tells how [request] ended in [language], null for no line. */
    private fun outcome(request: ApprovalRequest, ending: Ending, language: LanguageTag?): String? = when (ending) {
        Ending.Expired -> text(TextKeys.APPROVAL_EXPIRED, language)

        Ending.Withdrawn -> text(TextKeys.APPROVAL_WITHDRAWN, language)

        is Ending.Answered -> when (val answer = ending.answer) {
            is ApprovalAnswer.Allowed -> {
                val rule = request.always?.takeIf { answer.always }?.let { ending(it.display) }
                if (rule == null) {
                    text(TextKeys.APPROVAL_ALLOWED, language, "name" to named(answer.by))
                } else {
                    text(TextKeys.APPROVAL_ALLOWED_ALWAYS, language, "name" to named(answer.by), "rule" to rule)
                }
            }

            is ApprovalAnswer.Denied -> text(TextKeys.APPROVAL_DENIED, language, "name" to named(answer.by))

            else -> null
        }
    }

    private fun text(key: String, language: LanguageTag?, vararg args: Pair<String, Any?>): String =
        texts.text(key, language, *args)

    private suspend fun language(chat: ChatAddress): LanguageTag? = try {
        settings?.language(chat)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.debug("The language of chat {} is unknown: {}", chat, e.toString())
        null
    }

    /** One request on its way, with the messages that carry its buttons. */
    private class Pending(val request: ApprovalRequest) {
        val ending = CompletableDeferred<Ending>()
        val posted: MutableList<Posted> = CopyOnWriteArrayList()
        val noted = ApprovalMessages.Request(request.id, request.tool)
    }

    /** The messages of a request in [chat] in [language]. */
    private class Shown(val chat: ChatAddress, val language: LanguageTag?, val parts: List<FormattedText>)

    /** The message [id] that carries the buttons of a request in [chat], reading [text]. */
    private class Posted(
        val chat: ChatAddress,
        val target: Target,
        val id: Long,
        val text: FormattedText,
        val language: LanguageTag?,
    )

    private sealed interface Ending {
        val answer: ApprovalAnswer
        val logged: String

        /** How the request ended, as a quote of one of its messages tells. */
        val state: String

        class Answered(override val answer: ApprovalAnswer) : Ending {
            override val logged: String
                get() = when (answer) {
                    is ApprovalAnswer.Allowed -> "is allowed by ${answer.by.address}"
                    is ApprovalAnswer.Denied -> "is denied by ${answer.by.address}"
                    else -> "ends: $answer"
                }

            override val state: String
                get() = when (answer) {
                    is ApprovalAnswer.Allowed -> (if (answer.always) "allowed for good by " else "allowed by ") +
                        named(answer.by)

                    is ApprovalAnswer.Denied -> "denied by ${named(answer.by)}"

                    else -> "no longer waiting"
                }
        }

        data object Expired : Ending {
            override val answer: ApprovalAnswer = ApprovalAnswer.Expired
            override val logged: String = "expired"
            override val state: String = "expired"
        }

        data object Withdrawn : Ending {
            override val answer: ApprovalAnswer = ApprovalAnswer.Unavailable("the request was withdrawn")
            override val logged: String = "is withdrawn"
            override val state: String = "withdrawn"
        }
    }

    private companion object {
        const val PREFIX = "apv:"
        const val ALLOW = "a"
        const val ALWAYS = "A"
        const val DENY = "d"
        val PRESS = Regex("apv:([A-Za-z0-9_-]{1,32}):([aAd])")

        val UNREACHABLE = ApprovalAnswer.Unavailable("no administrator can be reached")
        val TOO_LARGE = ApprovalAnswer.Unavailable("too large to review")
    }
}

/** [user]'s name as an approver sees it, escaped. */
private fun named(user: ChatUser): String =
    shortened(ReviewText.escape(user.displayName) + user.username?.let { " (@${ReviewText.escape(it)})" }.orEmpty())

/** [text], cut at its start to [MAX_SHOWN] characters, so that a rule keeps its program and first word. */
private fun ending(text: String): String = cutStartWithEllipsis(text, MAX_SHOWN)

private fun shortened(text: String): String = cutWithEllipsis(text, MAX_SHOWN)

/** [bytes] in the largest unit that keeps a whole number in front. */
private fun readable(bytes: Long): String {
    val units = listOf("KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = "B"
    for (next in units) {
        if (value < UNIT) break
        value /= UNIT
        unit = next
    }
    return if (unit == "B") "$bytes B" else String.format(Locale.ROOT, "%.1f %s", value, unit)
}

/** [argv] as one command line, each word that a shell would split or expand in single quotes. */
private fun commandLine(argv: List<String>): String = argv.joinToString(" ") { word ->
    val plain = word.isNotEmpty() && word.all { it.isLetterOrDigit() || it in PLAIN }
    if (plain) word else "'${word.replace("'", "'\\''")}'"
}

/**
 * [head], whose first [bold] characters are bold, and [body] as code, folded when longer than [MAX_CODE_LINES], in
 * messages that leave room for an outcome and number every message after the first; null when they take more than
 * [MAX_PARTS].
 */
private fun split(head: String, bold: Int, body: String): List<FormattedText>? {
    val chunks = mutableListOf<String>()
    var rest = body.trimEnd()
    val entity = if (rest.lines().size > MAX_CODE_LINES) "expandable_blockquote" else "pre"
    var room = MAX_MESSAGE_LENGTH - OUTCOME_ROOM - head.length
    if (room <= 0) return null
    while (true) {
        if (chunks.size == MAX_PARTS) return null
        val end = cut(rest, room)
        chunks += rest.substring(0, end).trimEnd()
        rest = rest.substring(end).removePrefix("\n")
        if (rest.isEmpty()) break
        room = MAX_MESSAGE_LENGTH - OUTCOME_ROOM - NUMBER_ROOM
    }
    return chunks.mapIndexed { index, chunk ->
        val prefix = if (index == 0) head else "(${index + 1}/${chunks.size})\n"
        val spans = listOfNotNull(
            Span("bold", 0, bold).takeIf { index == 0 && bold > 0 },
            Span(entity, prefix.length, chunk.length).takeIf { chunk.isNotEmpty() },
        )
        FormattedText(prefix + chunk, spans)
    }
}

/** Where [text] is cut to fit [room]: at its last line break that fits, else at [room] outside a surrogate pair. */
private fun cut(text: String, room: Int): Int {
    if (text.length <= room) return text.length
    val lineBreak = text.lastIndexOf('\n', room)
    if (lineBreak > 0) return lineBreak
    return fittingLength(text, room)
}

/** How many messages one request may take. */
private const val MAX_PARTS = 4

private const val UNIT = 1024.0

/** The most lines a preview shows unfolded. */
private const val MAX_CODE_LINES = 12

/** The characters a request's last message keeps free for the line that tells how it ended. */
private const val OUTCOME_ROOM = 512

/** The characters a later message of a request keeps free for its number. */
private const val NUMBER_ROOM = 16

/** The characters of a name or a rule that a request shows. */
private const val MAX_SHOWN = 64

/** The characters a shell takes as part of a word. */
private const val PLAIN = "-_./=:,+@%"

private val logger = LoggerFactory.getLogger(ApprovalDesk::class.java)
