package org.unitedsoftware.alexandrite.channel.telegram

import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import java.util.concurrent.ConcurrentHashMap

/** The bots that the running channel instances poll, one instance per bot, and how each streams its replies. */
@Singleton
internal class TelegramBots {
    private val instances = HashMap<String, ChannelInstanceId>()
    private val streaming = ConcurrentHashMap<ChannelInstanceId, StreamingConfig>()

    /** Notes [config] as how the bot of [instance] streams its replies until it [stopped]. */
    fun started(instance: ChannelInstanceId, config: StreamingConfig) {
        streaming[instance] = config
    }

    fun stopped(instance: ChannelInstanceId) {
        streaming.remove(instance)
    }

    /** How the bot of [instance] streams its replies, null while it does not run. */
    fun streaming(instance: ChannelInstanceId): StreamingConfig? = streaming[instance]

    /** Claims the bot of id [bot] for [instance], or returns the other instance that polls it. */
    fun claim(bot: String, instance: ChannelInstanceId): ChannelInstanceId? = synchronized(instances) {
        instances.putIfAbsent(bot, instance)?.takeIf { it != instance }
    }

    fun release(bot: String, instance: ChannelInstanceId) {
        synchronized(instances) { instances.remove(bot, instance) }
    }
}
