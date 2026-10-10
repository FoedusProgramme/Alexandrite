package org.foedusprogramme.alexandrite.channel.telegram.reply

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApi
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiException
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure
import org.foedusprogramme.alexandrite.channel.telegram.api.LinkPreviewOptions
import org.foedusprogramme.alexandrite.channel.telegram.api.ReplyParameters
import org.foedusprogramme.alexandrite.channel.telegram.api.SendMessage
import org.foedusprogramme.alexandrite.channel.telegram.intake.BotSession
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatSettings
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/** Sends the channel's own notices in each chat's language. Placeholder until T2.6e's outbox takes them over. */
@ChannelInstanceScoped
internal class Notices(
    private val instance: ChannelInstance,
    private val api: BotApi,
    private val limiter: SendLimiter,
    private val texts: Texts,
    private val settings: ChatSettings?,
    private val session: BotSession,
) : Lifecycle {
    private val posting = SupervisorJob(instance.scope.coroutineContext[Job])
    private val scope = CoroutineScope(instance.scope.coroutineContext + posting)

    /** Sends the text [key] to [chat] without waiting, quoting [trigger] when [group]. */
    fun post(
        chat: ChatAddress,
        group: Boolean,
        trigger: ChannelMessageRef?,
        key: String,
        vararg args: Pair<String, Any?>,
    ) {
        scope.launch { send(chat, group, trigger, key, *args) }
    }

    /** Sends the text [key] to [chat], quoting [trigger] when [group]. */
    suspend fun send(
        chat: ChatAddress,
        group: Boolean,
        trigger: ChannelMessageRef?,
        key: String,
        vararg args: Pair<String, Any?>,
    ) {
        val request = SendMessage(
            chat.chat.toLong(),
            texts.text(key, language(chat), *args),
            chat.thread?.toLong(),
            linkPreviewOptions = LinkPreviewOptions(isDisabled = true),
            replyParameters = trigger?.takeIf { group }?.let { ReplyParameters(it.id.toLong(), true) },
        )
        try {
            limiter.message(request.chatId) { api.sendMessage(request) }
        } catch (e: BotApiException) {
            if (e.failure == BotApiFailure.UNAUTHORIZED) {
                session.revoked()
            } else {
                logger.warn("{} could not send the notice {} to chat {}: {}", instance.id, key, chat, e.message)
            }
        }
    }

    override suspend fun onDrain() {
        posting.children.toList().joinAll()
    }

    private suspend fun language(chat: ChatAddress): LanguageTag? = try {
        settings?.language(chat)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.debug("The language of chat {} is unknown: {}", chat, e.toString())
        null
    }
}

private val logger = LoggerFactory.getLogger(Notices::class.java)
