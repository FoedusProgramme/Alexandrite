package org.foedusprogramme.alexandrite.channel.telegram.reply

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import org.foedusprogramme.alexandrite.channel.telegram.StreamingConfig
import org.foedusprogramme.alexandrite.channel.telegram.TelegramBots
import org.foedusprogramme.alexandrite.channel.telegram.TelegramChannel
import org.foedusprogramme.alexandrite.channel.telegram.TelegramInstanceConfig
import org.foedusprogramme.alexandrite.channel.telegram.WORK
import org.foedusprogramme.alexandrite.channel.telegram.fake.FAKE_BOT_TOKEN
import org.foedusprogramme.alexandrite.channel.telegram.intake.AccessGate
import org.foedusprogramme.alexandrite.channel.telegram.intake.BotSession
import org.foedusprogramme.alexandrite.channel.telegram.intake.MemoryBotApi
import org.foedusprogramme.alexandrite.channel.telegram.intake.RecordingControl
import org.foedusprogramme.alexandrite.channel.telegram.intake.TestInstance
import org.foedusprogramme.alexandrite.channel.telegram.pairing.TelegramPairingDesk
import org.foedusprogramme.alexandrite.sdk.channel.Markup
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.channel.ReplyRequest
import org.foedusprogramme.alexandrite.sdk.channel.ReplySink
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.config.Secret
import org.foedusprogramme.alexandrite.testkit.testRecords
import org.foedusprogramme.alexandrite.testkit.testTexts
import org.foedusprogramme.alexandrite.testkit.testTurn
import java.time.Clock

const val DIRECT = 1001L
const val GROUP = -1001234500000L

/**
 * The channel's output over a [MemoryBotApi] on the virtual time of [test], with the limiter's default pace, where
 * [DIRECT], the direct chat after it and [GROUP] are paired.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class Output(test: TestScope, streaming: Boolean = true) {
    val api = MemoryBotApi().apply { clock = { test.currentTime } }
    val control = RecordingControl()
    private val instance = TestInstance(test.backgroundScope)
    private val config = TelegramInstanceConfig(
        Secret(FAKE_BOT_TOKEN),
        allowedChats = listOf(DIRECT, DIRECT + 1, GROUP),
        streaming = StreamingConfig(streaming),
    )
    val limiter = SendLimiter(timeSource = test.testScheduler.timeSource)
    private val session = BotSession(instance, config, api, TelegramBots(), control)
    val outbox = Outbox(instance, api, limiter, testTexts("alexandrite-channel-telegram"), null, session)
    private val gate = AccessGate(instance, config, TelegramPairingDesk(Clock.systemUTC(), testRecords()), outbox, null)
        .also { runBlocking { it.onStart() } }
    val channel = TelegramChannel(instance, config, gate, outbox, Typing(instance, api, limiter, session))

    /** Opens the reply of a turn in [chat], or its [thread], that answers the message [trigger]. */
    suspend fun open(chat: Long = DIRECT, trigger: String? = "41", thread: Long? = null): ReplySink {
        val address = address(chat, thread)
        val request = ReplyRequest(testTurn(chat = address), trigger?.let { ChannelMessageRef(address, it) })
        return channel.openReply(request)
    }

    /** The texts of the sends and edits, without the chat actions. */
    fun shown(): List<String> = api.sent.filter { it.method != "sendChatAction" }.map { "${it.method} ${it.text}" }

    fun address(chat: Long = DIRECT, thread: Long? = null): ChatAddress = ChatAddress(WORK, "$chat", thread?.toString())
}

fun markdown(text: String): OutboundMessage =
    OutboundMessage.builder(text, MessageKind.REPLY).markup(Markup.MARKDOWN).build()
