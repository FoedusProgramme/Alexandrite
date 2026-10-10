package org.foedusprogramme.alexandrite.channel.telegram

import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.config.Secret
import java.net.URI
import java.net.URISyntaxException

/** The config of one bot, bound as the instance section once the plugin contributes its channel. */
@Serializable
internal class TelegramInstanceConfig(
    val botToken: Secret,
    /** The Telegram user ids of this bot's operators. */
    val adminUserIds: List<Long> = emptyList(),
    /** The paired chats. */
    val allowedChats: List<Long> = emptyList(),
    val apiBaseUrl: String = "https://api.telegram.org",
    val polling: PollingConfig = PollingConfig(),
    val streaming: StreamingConfig = StreamingConfig(),
) {
    init {
        val token = botToken.reveal()
        require(token.isNotBlank()) { "botToken is empty" }
        require(TOKEN.matches(token)) {
            "botToken must be a bot token as BotFather gives it: digits, ':' and at least 30 letters, digits, " +
                "'_' or '-'"
        }
        adminUserIds.forEachIndexed { index, id ->
            require(id > 0) { "adminUserIds[$index] must be a Telegram user id, which is positive" }
        }
        allowedChats.forEachIndexed { index, id ->
            require(id != 0L) { "allowedChats[$index] must be a Telegram chat id, which is not 0" }
        }
        require(isHttpUrl(apiBaseUrl)) {
            "apiBaseUrl must be an http or https URL without user info, query or fragment"
        }
    }

    /** [apiBaseUrl] without a trailing `/`. */
    val apiBase: String get() = apiBaseUrl.trimEnd('/')
}

@Serializable
internal class PollingConfig(
    /** How long one long poll waits for updates, 0 for short polls. */
    val timeoutSeconds: Int = 25,
    /** The updates one poll fetches at most. */
    val limit: Int = 100,
    /** The longest pause between two failed polls. */
    val retryMaxSeconds: Int = 30,
) {
    init {
        require(timeoutSeconds in 0..50) { "polling.timeoutSeconds must lie between 0 and 50" }
        require(limit in 1..100) { "polling.limit must lie between 1 and 100" }
        require(retryMaxSeconds in 1..300) { "polling.retryMaxSeconds must lie between 1 and 300" }
    }
}

@Serializable
internal class StreamingConfig(
    /** Whether replies are previewed while the model writes them. */
    val enabled: Boolean = true,
    /** The shortest pause between two preview edits in a direct chat. */
    val editIntervalMillis: Long = 1000,
    /** The shortest pause between two preview edits in a group. */
    val groupEditIntervalMillis: Long = 3000,
) {
    init {
        require(editIntervalMillis in 0..MAX_INTERVAL) { "streaming.editIntervalMillis must lie between 0 and 60000" }
        require(groupEditIntervalMillis in 0..MAX_INTERVAL) {
            "streaming.groupEditIntervalMillis must lie between 0 and 60000"
        }
    }
}

private val TOKEN = Regex("[0-9]+:[A-Za-z0-9_-]{30,}")

private const val MAX_INTERVAL = 60_000L

private fun isHttpUrl(text: String): Boolean = try {
    val uri = URI(text)
    uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null
} catch (e: URISyntaxException) {
    false
}
