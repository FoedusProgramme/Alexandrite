package org.foedusprogramme.alexandrite.channel.telegram

import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.di.Singleton

/** The bots that the running channel instances poll, one instance per bot. */
@Singleton
internal class TelegramBots {
    private val instances = HashMap<String, ChannelInstanceId>()

    /** Claims the bot of id [bot] for [instance], or returns the other instance that polls it. */
    fun claim(bot: String, instance: ChannelInstanceId): ChannelInstanceId? = synchronized(instances) {
        instances.putIfAbsent(bot, instance)?.takeIf { it != instance }
    }

    fun release(bot: String, instance: ChannelInstanceId) {
        synchronized(instances) { instances.remove(bot, instance) }
    }
}
