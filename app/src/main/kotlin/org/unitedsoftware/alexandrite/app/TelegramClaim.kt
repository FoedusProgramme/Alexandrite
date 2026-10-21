package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.internal.pairing.holdsPairingCode
import org.unitedsoftware.alexandrite.internal.pairing.newPairingCode
import java.io.IOException
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal const val TELEGRAM_API = "https://api.telegram.org"

private val CLAIM_TIMEOUT = 10.minutes

/** How long one poll waits at Telegram for a message. */
private const val POLL_SECONDS = 10

/** The failed polls in a row after which the claim gives up. */
private const val FAILURES = 5
private val RETRY = 2.seconds

internal fun newClaimCode(): String = newPairingCode()

/** The Telegram user who sent the claim code from their direct chat with the bot, whose id is that chat's. */
internal class Claimant(val id: Long, val name: String, val username: String?) {
    val described: String get() = "$name${username?.let { " @$it" }.orEmpty()} ($id)"
}

/** What Telegram says about a bot token. */
internal sealed interface BotCheck {
    class Bot(val id: Long, val username: String?, val name: String? = null) : BotCheck

    class Refused(val reason: Text) : BotCheck
}

/** The bot for people: its name and username when Telegram gave them, and its id. */
internal fun described(cli: Cli, bot: BotCheck.Bot): String = when {
    bot.username == null -> cli.t("bot.plain", "id" to bot.id)
    bot.name == null -> cli.t("bot.username", "username" to bot.username, "id" to bot.id)
    else -> cli.t("bot.named", "name" to bot.name, "username" to bot.username, "id" to bot.id)
}

/** Asks the Bot API at [base] whom [token] belongs to. */
internal fun telegramBot(cli: Cli, base: String, token: String): BotCheck {
    val fetched = try {
        cli.machine.get("${base.trimEnd('/')}/bot$token/getMe")
    } catch (e: IOException) {
        return BotCheck.Refused(Text("bot.unreachable", "base" to base, "reason" to masked(e, token)))
    }
    if (fetched.status == 401 || fetched.status == 404) return BotCheck.Refused(Text("bot.refused"))
    if (fetched.status != 200) return BotCheck.Refused(Text("bot.http", "status" to fetched.status))
    val result = parsed(fetched.body)?.get("result") as? JsonObject
        ?: return BotCheck.Refused(Text("bot.no_bot"))
    val id = (result["id"] as? JsonPrimitive)?.content?.toLongOrNull()
        ?: return BotCheck.Refused(Text("bot.no_id"))
    return BotCheck.Bot(id, result["username"].text, result["first_name"].text)
}

/**
 * Shows a one-time code and polls the bot at [base] until a person sends it from a direct chat, ignoring every other
 * message; null after printing why nobody did.
 */
internal fun claimAdmin(
    cli: Cli,
    base: String,
    token: String,
    bot: BotCheck.Bot,
    timeout: Duration = CLAIM_TIMEOUT,
): Claimant? {
    val api = BotCalls(cli, base, token)
    val webhook = try {
        api.call("getWebhookInfo", JsonObject(emptyMap()))
    } catch (e: IOException) {
        cli.fail(cli.t("claim.unreachable", "base" to base, "reason" to masked(e, token)))
        return null
    }
    val hook = (webhook.result as? JsonObject)?.get("url").text
    if (!hook.isNullOrEmpty()) {
        cli.fail(cli.t("claim.webhook", "url" to hook))
        return null
    }
    val code = cli.claimCode()
    val ui = cli.ui
    if (ui.rich) {
        ui.say(cli.t("claim.send_code", "bot" to described(cli, bot)))
        ui.code(code)
    } else {
        ui.say(cli.t("claim.send", "bot" to described(cli, bot), "code" to code))
    }
    bot.username?.let { ui.say(cli.t("claim.link", "link" to "https://t.me/$it?start=$code")) }
    ui.say(cli.t("claim.waiting", "minutes" to timeout.inWholeMinutes))
    val deadline = System.nanoTime() + timeout.inWholeNanoseconds
    val sender = ui.busy({ cli.t("claim.countdown", "left" to left(deadline)) }) {
        awaitCode(cli, api, code, deadline, timeout, base, token)
    }
    if (sender == null) return null
    try {
        api.call("sendMessage", welcome(sender.id, cli.t("claim.welcome")))
    } catch (e: IOException) {
        // The claim stands without the greeting.
    }
    ui.good(cli.t("claim.done", "sender" to sender.described))
    return sender
}

/** `m:ss` until [deadline] of [System.nanoTime]. */
private fun left(deadline: Long): String {
    val seconds = ((deadline - System.nanoTime()) / 1_000_000_000).coerceAtLeast(0)
    return "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
}

/** The person who sends [code] to the bot before [deadline], null after printing why nobody did. */
private fun awaitCode(
    cli: Cli,
    api: BotCalls,
    code: String,
    deadline: Long,
    timeout: Duration,
    base: String,
    token: String,
): Claimant? {
    var offset: Long? = null
    var failures = 0
    while (System.nanoTime() < deadline) {
        val answer = try {
            api.call("getUpdates", updates(offset, POLL_SECONDS))
        } catch (e: IOException) {
            if (++failures >= FAILURES) {
                cli.fail(cli.t("claim.unreachable", "base" to base, "reason" to masked(e, token)))
                return null
            }
            cli.machine.sleep(RETRY)
            continue
        }
        when {
            answer.status == 409 -> {
                cli.fail(cli.t("claim.conflict", "reason" to (answer.description ?: "409")))
                return null
            }

            answer.status == 401 || answer.status == 404 -> {
                cli.fail(cli.t("bot.refused"))
                return null
            }

            !answer.ok || answer.result !is JsonArray -> {
                if (++failures >= FAILURES) {
                    cli.fail(cli.t("claim.failed", "reason" to (answer.description ?: "HTTP ${answer.status}")))
                    return null
                }
                cli.machine.sleep(RETRY)
                continue
            }
        }
        failures = 0
        for (update in answer.result) {
            val id = ((update as? JsonObject)?.get("update_id") as? JsonPrimitive)?.content?.toLongOrNull() ?: continue
            offset = id + 1
            val message = update["message"] as? JsonObject ?: continue
            val chat = message["chat"] as? JsonObject ?: continue
            val from = message["from"] as? JsonObject ?: continue
            if (chat["type"].text != "private" || (from["is_bot"] as? JsonPrimitive)?.booleanOrNull == true) continue
            val sender = sender(from) ?: continue
            if (!carries(message["text"].text, code)) {
                cli.say(cli.t("claim.ignored", "sender" to sender.described))
                continue
            }
            confirm(api, id + 1)
            return sender
        }
    }
    cli.fail(cli.t("claim.timeout", "minutes" to timeout.inWholeMinutes))
    return null
}

/** Whether [text] holds [code], however either is written. */
private fun carries(text: String?, code: String): Boolean = text != null && holdsPairingCode(text, code)

private fun sender(from: JsonObject): Claimant? {
    val id = (from["id"] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0 } ?: return null
    val name = listOfNotNull(from["first_name"].text, from["last_name"].text).joinToString(" ").ifEmpty { "$id" }
    return Claimant(id, name, from["username"].text)
}

/** Confirms the updates before [offset], so that the bot does not see the code once it runs. */
private fun confirm(api: BotCalls, offset: Long) {
    try {
        api.call("getUpdates", updates(offset, 0))
    } catch (e: IOException) {
        // The bot then sees the code once, as a message of a chat it pairs anyway.
    }
}

private fun updates(offset: Long?, timeout: Int): JsonObject = buildJsonObject {
    offset?.let { put("offset", it) }
    put("timeout", timeout)
}

private fun welcome(chat: Long, text: String): JsonObject = buildJsonObject {
    put("chat_id", chat)
    put("text", text)
}

/** The answer of a Bot API method. */
private class BotAnswer(val status: Int, val ok: Boolean, val result: JsonElement?, val description: String?)

/** Calls the methods of the bot [token] at [base]. */
private class BotCalls(private val cli: Cli, base: String, private val token: String) {
    private val root = "${base.trimEnd('/')}/bot$token"

    fun call(method: String, body: JsonObject): BotAnswer {
        val fetched = try {
            cli.machine.post("$root/$method", "$body")
        } catch (e: IOException) {
            throw IOException(masked(e, token), e)
        }
        val answer = parsed(fetched.body)
        return BotAnswer(
            fetched.status,
            (answer?.get("ok") as? JsonPrimitive)?.booleanOrNull == true,
            answer?.get("result"),
            answer?.get("description").text,
        )
    }
}

private fun parsed(body: String): JsonObject? = try {
    Json.parseToJsonElement(body) as? JsonObject
} catch (e: IllegalArgumentException) {
    null
}

private fun masked(e: IOException, token: String): String = (e.message ?: "$e").replace(token, "***")
