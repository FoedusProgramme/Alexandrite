package org.unitedsoftware.alexandrite.channel.telegram

import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.di.Singleton

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
