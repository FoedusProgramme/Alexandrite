package org.unitedsoftware.alexandrite.channel.telegram.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.channel.common.pairing.PairedChat
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApi
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiException
import org.unitedsoftware.alexandrite.channel.telegram.intake.AccessGate
import org.unitedsoftware.alexandrite.channel.telegram.intake.ChatTitles
import org.unitedsoftware.alexandrite.channel.telegram.pairing.TelegramPairingDesk
import org.unitedsoftware.alexandrite.channel.telegram.reply.Deliveries
import org.unitedsoftware.alexandrite.channel.telegram.reply.Outbox
import org.unitedsoftware.alexandrite.channel.telegram.reply.SendLimiter
import org.unitedsoftware.alexandrite.channel.telegram.reply.Target
import org.unitedsoftware.alexandrite.channel.telegram.reply.targetOf
import org.unitedsoftware.alexandrite.internal.tool.ToolRefusal
import org.unitedsoftware.alexandrite.sdk.channel.ChannelInstance
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import java.util.concurrent.ConcurrentHashMap

/** The desks of the running bots, by channel instance, where the Telegram tools act. */
@Singleton
internal class TelegramDesks {
    private val desks = ConcurrentHashMap<ChannelInstanceId, ChatDesk>()

    fun open(desk: ChatDesk) {
        desks[desk.instance] = desk
    }

    fun close(desk: ChatDesk) {
        desks.remove(desk.instance, desk)
    }

    /** The desk of the bot of [instance], null when no such bot runs. */
    fun of(instance: ChannelInstanceId): ChatDesk? = desks[instance]
}

/** What the Telegram tools do in the chats of one bot, which must be paired. */
@ChannelInstanceScoped
internal class ChatDesk(
    channel: ChannelInstance,
    private val api: BotApi,
    private val outbox: Outbox,
    private val deliveries: Deliveries,
    private val limiter: SendLimiter,
    private val gate: AccessGate,
    private val pairings: TelegramPairingDesk,
    private val titles: ChatTitles,
    private val turns: TurnMessages,
    private val users: KnownUsers,
    private val desks: TelegramDesks,
) : Lifecycle {
    val instance: ChannelInstanceId = channel.id

    override suspend fun onOpen() {
        desks.open(this)
    }

    override suspend fun onClose() {
        desks.close(this)
    }

    /** The Telegram chat of [chat], a [ToolRefusal] when it names none or is not paired. */
    fun target(chat: ChatAddress): Target {
        val target = targetOf(chat)?.takeIf { chat.instance == instance }
            ?: throw ToolRefusal("$chat is no chat of this bot.")
        if (!gate.isPaired(chat)) throw ToolRefusal("$chat is not paired with this bot.")
        return target
    }

    /** How people know [chat]: its title, or its id for a direct chat. */
    fun name(chat: ChatAddress): String = titles.title(chat)?.let { "\"$it\"" } ?: "chat ${chat.chat}"

    /**
     * The message of [turn]'s chat that [selector] names: `this` (the message that started the turn), `quoted` (the
     * message it replies to) or a message id; [fallback] stands for a missing [selector].
     */
    fun message(turn: TurnInfo, selector: String?, fallback: String): Long {
        val chosen = selector?.trim()?.ifEmpty { null } ?: fallback
        chosen.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        val started = turns.of(turn.lineage?.rootTurn ?: turn.id)
        return when (chosen) {
            THIS -> started?.message ?: throw ToolRefusal("This turn started from no message the bot can name.")

            QUOTED ->
                started?.replied
                    ?: throw ToolRefusal("The message that started this turn replies to no message: give a message id.")

            else -> throw ToolRefusal("Give 'message' as \"$THIS\", \"$QUOTED\" or a message id.")
        }
    }

    /** The id of [user] in [chat]: a user id, or the @username of someone who wrote there lately. */
    fun user(chat: Long, user: String): Long = user.trim().toLongOrNull()?.takeIf { it > 0 }
        ?: users.id(chat, user)
        ?: throw ToolRefusal("No one named $user wrote here lately: give their user id.")

    /** The paired chats of this bot. */
    suspend fun paired(): List<PairedChat> = pairings.paired().filter { it.chat.instance == instance }

    /** Calls [method] with [params] for [target] at the chat's pace, a [ToolRefusal] when Telegram refuses it. */
    suspend fun act(target: Target, method: String, params: JsonObject): JsonElement =
        call(method, params) { outbox.paced(target, it) }

    /** Sends what [method] with [params] posts in [chat], after what the chat's open replies hold so far. */
    suspend fun post(chat: ChatAddress, method: String, params: JsonObject): JsonElement {
        deliveries.flush(chat)
        return act(target(chat), method, params)
    }

    /** Calls the read [method] with [params] at the bot's pace, a [ToolRefusal] when Telegram refuses it. */
    suspend fun read(method: String, params: JsonObject): JsonElement =
        call(method, params) { limiter.message(null, it) }

    private suspend fun call(
        method: String,
        params: JsonObject,
        pacing: suspend (suspend () -> JsonElement) -> JsonElement,
    ): JsonElement = try {
        pacing { api.act(method, params) }
    } catch (e: BotApiException) {
        outbox.checkToken(e)
        throw ToolRefusal(e.message ?: "$method failed.")
    }

    companion object {
        const val THIS = "this"
        const val QUOTED = "quoted"
    }
}
