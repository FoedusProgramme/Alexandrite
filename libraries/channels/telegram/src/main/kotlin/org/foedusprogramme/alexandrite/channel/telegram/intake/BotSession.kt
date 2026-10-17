package org.foedusprogramme.alexandrite.channel.telegram.intake

import kotlinx.coroutines.delay
import org.foedusprogramme.alexandrite.channel.telegram.TelegramBots
import org.foedusprogramme.alexandrite.channel.telegram.TelegramInstanceConfig
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApi
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiException
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure
import org.foedusprogramme.alexandrite.channel.telegram.configPath
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.config.ConfigException
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.runtime.RuntimeControl
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/** The bot of a channel instance: checked at start, and the runtime stopped once Telegram drops its token. */
@ChannelInstanceScoped
internal class BotSession(
    private val instance: ChannelInstance,
    private val config: TelegramInstanceConfig,
    private val api: BotApi,
    private val bots: TelegramBots,
    private val control: RuntimeControl,
) : Lifecycle {
    private val bot = config.botToken.reveal().substringBefore(':')
    private val revoked = AtomicBoolean()

    @Volatile
    private var me: BotIdentity? = null

    /** The bot, known from the start of the instance on. */
    val identity: BotIdentity get() = checkNotNull(me) { "The bot of ${instance.id} is not known before its start." }

    override suspend fun onStart() {
        bots.claim(bot, instance.id)?.let { other ->
            throw ConfigException(
                path("botToken"),
                "the bot of this token (id $bot) is the bot of $other already: give each instance a bot of its own",
            )
        }
        try {
            check()
        } catch (e: Throwable) {
            bots.release(bot, instance.id)
            throw e
        }
    }

    override fun onStop() {
        bots.release(bot, instance.id)
    }

    /** Stops the runtime because Telegram no longer accepts the bot's token, once. */
    fun revoked() {
        if (!revoked.compareAndSet(false, true)) return
        val reason = "Telegram no longer accepts the bot token of ${instance.id}"
        logger.error("{}: stopping the runtime", reason)
        control.stop(StopRequest.failure(reason))
    }

    private suspend fun check() {
        val user = reached { api.getMe() }
        if (reached { api.getWebhookInfo() }.url.isNotEmpty()) {
            throw ConfigException(
                path(null),
                "Telegram holds a webhook for this bot, and long polling cannot run beside it: delete the webhook",
            )
        }
        val username = user.username.orEmpty()
        me = BotIdentity(user.id, username)
        val reads = if (user.canReadAllGroupMessages == true) "yes" else "no"
        logger.info("{} is @{} (id {}), reads all group messages: {}", instance.id, username, user.id, reads)
        if (sendsTokenInClear(config.apiBase)) {
            logger.warn(
                "{} reaches the Bot API at {} over plain http, so its bot token crosses the network unencrypted",
                instance.id,
                config.apiBase,
            )
        }
        if (config.adminUserIds.isEmpty()) {
            logger.warn(
                "{}: no admin is configured, so nobody may run admin commands and agents offer tools to nobody",
                instance.id,
            )
        }
    }

    /** What [call] returns, tried [TRIES] times while Telegram cannot be reached. */
    private suspend fun <T> reached(call: suspend () -> T): T {
        var wait = 1.seconds
        var attempt = 1
        while (true) {
            try {
                return call()
            } catch (e: BotApiException) {
                when (e.failure) {
                    BotApiFailure.UNAUTHORIZED ->
                        throw ConfigException(path("botToken"), "Telegram does not accept this bot token", e)

                    BotApiFailure.TRANSIENT, BotApiFailure.PROTOCOL, BotApiFailure.RATE_LIMITED -> {
                        if (attempt == TRIES) {
                            val message = "${instance.id} cannot reach Telegram after $TRIES tries: ${e.message}"
                            throw IOException(message, e)
                        }
                        logger.warn("{} cannot reach Telegram, trying again in {}: {}", instance.id, wait, e.message)
                        delay(maxOf(wait, e.retryAfter ?: wait))
                    }

                    else -> throw e
                }
            }
            wait *= 2
            attempt++
        }
    }

    private fun path(key: String?): String = configPath(instance.id, key)

    private companion object {
        const val TRIES = 3
    }
}

/** Whether [url] is plain http to a host other than this one. */
private fun sendsTokenInClear(url: String): Boolean {
    val uri = URI(url)
    val host = uri.host.removePrefix("[").removeSuffix("]").lowercase()
    val loopback = host == "localhost" || host.endsWith(".localhost") || host == "::1" || LOOPBACK_V4.matches(host)
    return uri.scheme.equals("http", ignoreCase = true) && !loopback
}

private val LOOPBACK_V4 = Regex("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")

private val logger = LoggerFactory.getLogger(BotSession::class.java)
