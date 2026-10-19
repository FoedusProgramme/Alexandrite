package org.unitedsoftware.alexandrite.automation.schedule

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.channel.Channel
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatSettings
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.plugin.PluginScope
import java.time.Clock
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

/** Tells chats what became of their tasks, in each chat's language and zone. */
@Singleton
internal class Notices(
    private val directory: ChannelDirectory,
    private val settings: ChatSettings?,
    private val texts: Texts,
    private val clock: Clock,
    private val scope: PluginScope,
) {
    /** Sends [chat] the notice that [compose] words, once the chat's channel runs. */
    fun send(chat: ChatAddress, compose: (Wording) -> String) {
        scope.launch {
            try {
                deliver(chat, compose)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Cannot tell chat {} of its scheduled task: {}", chat, e.toString())
            }
        }
    }

    /** The language and zone of [chat]. */
    suspend fun wording(chat: ChatAddress): Wording =
        Wording(texts, settings?.language(chat), settings?.zone(chat) ?: clock.zone)

    private suspend fun deliver(chat: ChatAddress, compose: (Wording) -> String) {
        val channel = channel(chat) ?: return
        val text = compose(wording(chat))
        channel.send(chat, OutboundMessage.builder(text, MessageKind.NOTICE).build())
    }

    /** The channel of [chat], waiting a while for it to open, null when it does not. */
    private suspend fun channel(chat: ChatAddress): Channel? {
        if (chat.instance !in directory.instances) {
            logger.warn("Cannot tell chat {} of its scheduled task: its channel instance is not configured", chat)
            return null
        }
        repeat(CHANNEL_TRIES) {
            directory.channel(chat.instance)?.let { return it }
            delay(1.seconds)
        }
        logger.warn("Cannot tell chat {} of its scheduled task: its channel does not run", chat)
        return null
    }

    private companion object {
        const val CHANNEL_TRIES = 120
    }
}

/** The plugin's texts in [language], with times in [zone]. */
internal class Wording(private val texts: Texts, val language: LanguageTag?, val zone: ZoneId) {
    fun text(key: String, vararg args: Pair<String, Any?>): String = texts.text(key, language, *args)
}

private val logger = LoggerFactory.getLogger(Notices::class.java)
