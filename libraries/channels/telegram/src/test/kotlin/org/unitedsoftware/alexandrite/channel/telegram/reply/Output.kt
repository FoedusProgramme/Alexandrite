package org.unitedsoftware.alexandrite.channel.telegram.reply

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import org.unitedsoftware.alexandrite.channel.telegram.StreamingConfig
import org.unitedsoftware.alexandrite.channel.telegram.TelegramBots
import org.unitedsoftware.alexandrite.channel.telegram.TelegramChannel
import org.unitedsoftware.alexandrite.channel.telegram.TelegramInstanceConfig
import org.unitedsoftware.alexandrite.channel.telegram.WORK
import org.unitedsoftware.alexandrite.channel.telegram.approval.ApprovalDesk
import org.unitedsoftware.alexandrite.channel.telegram.approval.ApprovalMessages
import org.unitedsoftware.alexandrite.channel.telegram.fake.FAKE_BOT_TOKEN
import org.unitedsoftware.alexandrite.channel.telegram.intake.AccessGate
import org.unitedsoftware.alexandrite.channel.telegram.intake.BotSession
import org.unitedsoftware.alexandrite.channel.telegram.intake.ChatTitles
import org.unitedsoftware.alexandrite.channel.telegram.intake.CommandRules
import org.unitedsoftware.alexandrite.channel.telegram.intake.MemoryBotApi
import org.unitedsoftware.alexandrite.channel.telegram.intake.RecordingControl
import org.unitedsoftware.alexandrite.channel.telegram.intake.TestInstance
import org.unitedsoftware.alexandrite.channel.telegram.pairing.TelegramPairingDesk
import org.unitedsoftware.alexandrite.channel.telegram.tools.ChatDesk
import org.unitedsoftware.alexandrite.channel.telegram.tools.KnownUsers
import org.unitedsoftware.alexandrite.channel.telegram.tools.TelegramDesks
import org.unitedsoftware.alexandrite.channel.telegram.tools.TurnMessages
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.channel.ReplyRequest
import org.unitedsoftware.alexandrite.sdk.channel.ReplySink
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.testkit.TestChatStates
import org.unitedsoftware.alexandrite.testkit.testRecords
import org.unitedsoftware.alexandrite.testkit.testTexts
import org.unitedsoftware.alexandrite.testkit.testTurn
import java.time.Clock

const val DIRECT = 1001L
const val GROUP = -1001234500000L

/** A text of three messages, cut at its blank lines. */
val THREE_MESSAGES = "a".repeat(4_000) + "\n\n" + "b".repeat(4_000) + "\n\n" + "c".repeat(100)

/**
 * The channel's output over a [MemoryBotApi] on the virtual time of [test], with the limiter's default pace, where
 * [DIRECT], the direct chat after it and [GROUP] are paired.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class Output(
    test: TestScope,
    streaming: Boolean = true,
    mode: StreamingMode = StreamingMode.EDIT,
    quote: Boolean = true,
) {
    val api = MemoryBotApi().apply { clock = { test.currentTime } }
    val control = RecordingControl()
    private val instance = TestInstance(test.backgroundScope)
    private val config = TelegramInstanceConfig(
        Secret(FAKE_BOT_TOKEN),
        allowedChats = listOf(DIRECT, DIRECT + 1, GROUP),
        streaming = StreamingConfig(streaming, mode = mode, quote = quote),
    )

    /** The chats' own `/streaming` choices. */
    val choices = StreamingChoices(TestChatStates().of("alexandrite-channel-telegram"))
    val limiter = SendLimiter(timeSource = test.testScheduler.timeSource)
    private val session = BotSession(instance, config, api, TelegramBots(), control)
    private val texts = testTexts("alexandrite-channel-telegram")
    val outbox = Outbox(instance, api, limiter, texts, null, session)

    /** The bot's messages that answered an admin. */
    val vouched = VouchedReplies(instance, testRecords())
    private val pairings = TelegramPairingDesk(Clock.systemUTC(), testRecords())
    private val gate = AccessGate(instance, config, pairings, outbox, null, null)
        .also { runBlocking { it.onStart() } }
    private val approvals =
        ApprovalDesk(instance, config, gate, outbox, limiter, api, texts, null, ChatTitles(), ApprovalMessages())
    val deliveries =
        Deliveries(
            instance,
            config,
            gate,
            outbox,
            Typing(instance, api, limiter, session),
            texts,
            ReplyStyles(config, choices),
            vouched,
        )

    /** The titles of the groups the bot heard from. */
    val titles = ChatTitles()

    /** The message that started each turn, as the intake and the opened replies note it. */
    val turns = TurnMessages()

    /** The desk where the Telegram tools act on this output. */
    val desk = ChatDesk(
        instance,
        api,
        outbox,
        deliveries,
        limiter,
        gate,
        pairings,
        titles,
        turns,
        KnownUsers(),
        TelegramDesks(),
    )
    val channel = TelegramChannel(
        instance,
        config,
        gate,
        deliveries,
        approvals,
        CommandRules(emptyList(), null),
        titles,
        turns,
    )

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
