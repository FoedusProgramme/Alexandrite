package org.foedusprogramme.alexandrite.channel.telegram.intake

import org.foedusprogramme.alexandrite.channel.telegram.TelegramInstanceConfig
import org.foedusprogramme.alexandrite.channel.telegram.api.Message
import org.foedusprogramme.alexandrite.channel.telegram.api.Update
import org.foedusprogramme.alexandrite.channel.telegram.approval.ApprovalDesk
import org.foedusprogramme.alexandrite.channel.telegram.approval.ApprovalMessages
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.turn.Submission
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Dispatches one update, or the updates of one album, through the access gate and the reader to the admissions or the
 * command router, or a button press to the approval desk.
 */
@ChannelInstanceScoped
internal class Intake(
    private val instance: ChannelInstance,
    private val config: TelegramInstanceConfig,
    private val session: BotSession,
    private val gate: AccessGate,
    private val commands: CommandRouter,
    private val admissions: Admissions,
    private val approvals: ApprovalDesk,
    private val titles: ChatTitles,
    private val approvalMessages: ApprovalMessages,
    private val files: TelegramFiles,
) : Lifecycle {
    private val reader by lazy {
        UpdateReader(instance.id, config.adminUserIds.toSet(), session.identity, approvalMessages, files::content)
    }

    private val lock = Any()
    private val albums = Albums(instance.scope, ALBUM_WAIT, lock, ::released)

    /** Dispatches [update] without waiting for the network, or holds it as a part of an album or behind one. */
    fun dispatch(update: Update): Dispatch {
        update.callbackQuery?.let { query ->
            approvals.press(query, reader.user(query.from))
            return Dispatch.DONE
        }
        val message = update.message ?: return ignored(update, "it holds no message")
        return synchronized(lock) {
            if (albums.hold(reader.chatOf(message), message)) Dispatch.DONE else dispatch(listOf(message))
        }
    }

    /** Dispatches the held albums at once, and the messages behind them. */
    override suspend fun onClose() {
        albums.releaseAll()
    }

    /** Dispatches [parts] that were held, which a closing agent drops. */
    private fun released(parts: List<Message>) {
        if (dispatch(parts) == Dispatch.CLOSING) {
            val ids = parts.map { it.messageId }
            logger.warn("{} drops messages {} of chat {}: the agent is closing", instance.id, ids, parts[0].chat.id)
        }
    }

    /** Dispatches [parts], one message or an album. */
    private fun dispatch(parts: List<Message>): Dispatch {
        val message = parts.first()
        val chat = reader.chatOf(message)
        val kind = reader.kindOf(message.chat)
        val trigger = ChannelMessageRef(chat, "${message.messageId}")
        val requester = message.from?.let { reader.user(it, message.senderChat) }
        if (!gate.admit(chat, kind, message.chat.title, trigger, requester, parts.any(reader::addressed))) {
            return Dispatch.DONE
        }
        titles.note(chat, message.chat.title)
        return when (val inbound = reader.read(parts)) {
            is Inbound.Ignored -> ignored(message.messageId, inbound.why)

            is Inbound.Unsupported -> {
                admissions.unsupported(inbound.chat, inbound.trigger)
                Dispatch.DONE
            }

            is Inbound.Message -> admissions.submit(Submission.Message(inbound.message), trigger, kind)

            is Inbound.Command -> commands.route(inbound, kind, trigger)
        }
    }

    private fun ignored(update: Update, why: String): Dispatch {
        logger.debug("{} ignores update {}: {}", instance.id, update.updateId, why)
        return Dispatch.DONE
    }

    private fun ignored(message: Long, why: String): Dispatch {
        logger.debug("{} ignores message {}: {}", instance.id, message, why)
        return Dispatch.DONE
    }

    private companion object {
        /** How long an album waits for its next part. */
        val ALBUM_WAIT = 1500.milliseconds
    }
}

private val logger = LoggerFactory.getLogger(Intake::class.java)
