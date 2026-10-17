package org.foedusprogramme.alexandrite.channel.telegram

import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.channel.telegram.intake.CommandRules
import org.foedusprogramme.alexandrite.channel.telegram.intake.Who
import org.foedusprogramme.alexandrite.channel.telegram.reply.StreamingMode
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.config.ConfigSection
import org.foedusprogramme.alexandrite.sdk.config.Secret
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import java.net.URI
import java.net.URISyntaxException

/** The config of one bot. */
@ChannelInstanceScoped
@ConfigSection
@Serializable
internal class TelegramInstanceConfig(
    val botToken: Secret,
    /** The Telegram user ids of this bot's operators. */
    val adminUserIds: List<Long> = emptyList(),
    /** The chats paired beside those whose codes were approved. */
    val allowedChats: List<Long> = emptyList(),
    val apiBaseUrl: String = "https://api.telegram.org",
    val polling: PollingConfig = PollingConfig(),
    val streaming: StreamingConfig = StreamingConfig(),
    /** How long an approval request waits for an admin's answer. */
    val approvalMinutes: Int = 10,
    /** Whether the bot publishes its command menu. */
    val commandMenu: Boolean = true,
    /** Who may run each named command of another plugin, which only admins may run otherwise. */
    val commands: Map<String, Who> = emptyMap(),
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
        require(approvalMinutes in 1..MAX_APPROVAL_MINUTES) { "approvalMinutes must lie between 1 and 1440" }
        for (name in commands.keys) {
            require(COMMAND.matches(name)) {
                "commands.$name must name a command: lowercase letters, digits and '_', 32 characters at most"
            }
            require(name !in CommandRules.MATRIX) { "commands.$name: /$name keeps the rule of the default commands" }
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
    /** How a chat sees a reply come, unless it chose otherwise with `/streaming`. */
    val mode: StreamingMode = StreamingMode.EDIT,
    /** Whether a reply quotes the message it answers, unless the chat chose otherwise. */
    val quote: Boolean = true,
) {
    init {
        require(editIntervalMillis in 0..MAX_INTERVAL) { "streaming.editIntervalMillis must lie between 0 and 60000" }
        require(groupEditIntervalMillis in 0..MAX_INTERVAL) {
            "streaming.groupEditIntervalMillis must lie between 0 and 60000"
        }
    }
}

/** The config path of [key] in the config of [instance], of the instance itself when [key] is null. */
internal fun configPath(instance: ChannelInstanceId, key: String? = null): String =
    listOfNotNull("channels.telegram.instances.${instance.name}", key).joinToString(".")

private val TOKEN = Regex("[0-9]+:[A-Za-z0-9_-]{30,}")

private val COMMAND = Regex("[a-z0-9_]{1,32}")

private const val MAX_INTERVAL = 60_000L

private const val MAX_APPROVAL_MINUTES = 1440

private fun isHttpUrl(text: String): Boolean = try {
    val uri = URI(text)
    uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null
} catch (e: URISyntaxException) {
    false
}
