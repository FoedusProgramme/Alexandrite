package org.foedusprogramme.alexandrite.testkit

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.foedusprogramme.alexandrite.sdk.channel.ChannelCapabilities
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.channel.Markup
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.channel.ReplyEnd
import org.foedusprogramme.alexandrite.sdk.channel.ReplyRequest
import org.foedusprogramme.alexandrite.sdk.channel.ReplySink
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.chat.ReplyTarget
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.RefusalReason
import org.foedusprogramme.alexandrite.sdk.turn.Submission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RecordingChannelTest {
    private val submitter = RecordingTurnSubmitter()
    private val channel = RecordingChannel(admins = setOf("ada"), partLength = 10, submitter = submitter)
    private val turn = testTurn()

    private fun turn(
        id: String,
        block: TurnInfo.Builder.() -> Unit = {
        },
    ): TurnInfo = testTurn { id(TurnId(id)).apply(block) }

    private fun open(turn: TurnInfo = this.turn): ReplySink = blocking { channel.openReply(ReplyRequest(turn, null)) }

    private fun reply(text: String, kind: MessageKind = MessageKind.REPLY): OutboundMessage =
        OutboundMessage.builder(text, kind).build()

    // Recording.

    @Test
    fun `the channel records replies and sends in the order they happen`() {
        val other = turn("other")
        val request = ReplyRequest(turn, ChannelMessageRef(turn.chat, "in-1"))

        blocking {
            val sink = channel.openReply(request)
            sink.preview(0, "Hel")
            sink.preview(0, "Hello")
            val second = channel.openReply(ReplyRequest(other, null))
            channel.send(channel.chat("elsewhere"), reply("Notice", MessageKind.NOTICE))
            sink.preview(1, "Next")
            sink.complete(reply("Hello. Next"))
            second.abandon(ReplyEnd.CANCELLED)
        }

        val chat = turn.chat
        assertEquals(
            listOf(
                RecordingChannel.Event.Opened(request),
                RecordingChannel.Event.Previewed(turn.id, 0, "Hel"),
                RecordingChannel.Event.Previewed(turn.id, 0, "Hello"),
                RecordingChannel.Event.Opened(ReplyRequest(other, null)),
                RecordingChannel.Event.Sent(
                    channel.chat("elsewhere"),
                    reply("Notice", MessageKind.NOTICE),
                    Delivery.Delivered(listOf(ChannelMessageRef(channel.chat("elsewhere"), "out-1"))),
                ),
                RecordingChannel.Event.Previewed(turn.id, 1, "Next"),
                RecordingChannel.Event.Completed(
                    turn.id,
                    reply("Hello. Next"),
                    Delivery.Delivered(listOf(ChannelMessageRef(chat, "out-2"), ChannelMessageRef(chat, "out-3"))),
                ),
                RecordingChannel.Event.Abandoned(other.id, ReplyEnd.CANCELLED),
            ),
            channel.events,
        )
        val recorded = channel.reply(turn.id)
        assertEquals(
            listOf(
                RecordedReply.Preview(0, "Hel"),
                RecordedReply.Preview(0, "Hello"),
                RecordedReply.Preview(1, "Next"),
            ),
            recorded.previews,
        )
        assertEquals(mapOf(0 to "Hello", 1 to "Next"), recorded.segments)
        assertEquals(reply("Hello. Next"), recorded.completed)
        assertIs<Delivery.Delivered>(recorded.delivery)
        assertNull(recorded.abandoned)
        assertTrue(recorded.ended)
        assertEquals(ReplyEnd.CANCELLED, channel.reply(other.id).abandoned)
        assertNull(channel.reply(other.id).completed)
        assertEquals(listOf(turn, other), channel.replies.map { it.turn })
        assertEquals(1, channel.sent.size)
        assertEquals(emptyList(), channel.violations)
        assertFailsWith<NoSuchElementException> { channel.reply(TurnId("none")) }
    }

    @Test
    fun `a delivery reaches the chat as one message per part unless it is scripted`() {
        val chat = channel.chat("busy")
        val gone = Delivery.NotDelivered(DeliveryFailure.CHAT_GONE, "left")
        val limited = Delivery.NotDelivered(DeliveryFailure.RATE_LIMITED, retryAfter = 2.seconds)
        channel.scriptDeliveries(gone, chat = chat)
        channel.scriptDeliveries(limited)

        val deliveries = blocking {
            listOf(
                channel.send(chat, reply("first")),
                channel.send(channel.chat("calm"), reply("second")),
                channel.send(chat, reply("third")),
                channel.send(chat, reply("a text of 25 characters..")),
            )
        }

        assertEquals(gone, deliveries[0])
        assertEquals(limited, deliveries[1])
        assertEquals(Delivery.Delivered(listOf(ChannelMessageRef(chat, "out-1"))), deliveries[2])
        assertEquals(3, (deliveries[3] as Delivery.Delivered).messages.size)
        assertEquals(3, blocking { channel.partsNeeded(chat, "a text of 25 characters..", Markup.PLAIN) })
        assertEquals(1, blocking { channel.partsNeeded(chat, "", Markup.MARKDOWN) })
    }

    @Test
    fun `a reply that needs more parts than the chat allows is too long`() {
        channel.scriptCapabilities(ChannelCapabilities.builder().maxPartsPerReply(2).build(), turn.chat)

        val delivery = blocking { open().complete(reply("a text of 25 characters..")) }

        val failure = assertIs<Delivery.NotDelivered>(delivery)
        assertEquals(DeliveryFailure.TOO_LONG, failure.kind)
        assertEquals(delivery, channel.reply(turn.id).delivery)
    }

    @Test
    fun `capabilities are scripted per chat, for every other chat, or are the defaults`() {
        val plain = ChannelCapabilities.builder().build()
        val streaming = ChannelCapabilities.builder().streaming(true).build()
        val fresh = RecordingChannel()

        channel.scriptCapabilities(plain, channel.chat("quiet"))
        channel.scriptCapabilities(streaming)

        assertEquals(RecordingChannel.DEFAULT_CAPABILITIES, blocking { fresh.capabilities(fresh.chat()) })
        assertEquals(plain, blocking { channel.capabilities(channel.chat("quiet")) })
        assertEquals(streaming, blocking { channel.capabilities(channel.chat("other")) })
        val defaults = RecordingChannel.DEFAULT_CAPABILITIES
        assertTrue(defaults.streaming && defaults.finalReplacesPreview && defaults.proactive)
        assertEquals(setOf(Markup.PLAIN, Markup.MARKDOWN), defaults.markups)
    }

    @Test
    fun `a test awaits a reply and its end`() {
        val reply = blocking {
            coroutineScope {
                val awaited = async(start = CoroutineStart.UNDISPATCHED) { channel.awaitReply(turn.id).awaitEnd() }
                val sink = channel.openReply(ReplyRequest(turn, null))
                sink.preview(0, "Hi")
                sink.complete(reply("Hi!"))
                awaited.await()
            }
        }

        assertEquals(reply("Hi!"), reply.completed)
        val previewed = blocking { channel.awaitEvent { it is RecordingChannel.Event.Previewed } }
        assertEquals(RecordingChannel.Event.Previewed(turn.id, 0, "Hi"), previewed)
    }

    // The rules of Channel and ReplySink.

    private fun assertViolation(expected: String, call: suspend () -> Unit) {
        val before = channel.events
        val error = assertFailsWith<AssertionError> { blocking { call() } }
        assertEquals("Recording channel test:main saw $expected", error.message)
        assertEquals(expected, channel.violations.last().removePrefix("Recording channel test:main saw "))
        assertEquals(before, channel.events)
    }

    @Test
    fun `a reply sink is used in order and ends once`() {
        val completed = open(turn("completed"))
        val abandoned = open(turn("abandoned"))
        val streaming = open(turn("streaming"))
        blocking {
            completed.complete(reply("Done"))
            abandoned.abandon(ReplyEnd.FAILED)
            streaming.preview(1, "Second")
        }

        assertViolation("a preview of turn completed after its reply ended.") { completed.preview(0, "late") }
        assertViolation("the reply of turn completed completed after it ended.") { completed.complete(reply("again")) }
        assertViolation("the reply of turn completed abandoned after it ended.") { completed.abandon(ReplyEnd.FAILED) }
        assertViolation("the reply of turn abandoned completed after it ended.") { abandoned.complete(reply("late")) }
        assertViolation("a preview of turn streaming in segment 0 after segment 1 froze it.") {
            streaming.preview(0, "First")
        }
        assertViolation("a preview of turn streaming in segment -1.") { streaming.preview(-1, "Before") }
        assertEquals(6, channel.violations.size)
    }

    @Test
    fun `only a turn of the instance that replies to its chat opens one reply`() {
        open()
        val foreign = testTurn(chat = testChat(instance = ChannelInstanceId(ChannelType("test"), "work")))
        val delegated = testDelegatedTurn(turn)

        assertViolation("a second reply of turn test-turn.") { channel.openReply(ReplyRequest(turn, null)) }
        assertViolation("a reply of turn test-turn opened for test:work:chat.") {
            channel.openReply(ReplyRequest(foreign, null))
        }
        assertViolation("a reply of turn test-run-turn, which replies to its caller.") {
            channel.openReply(ReplyRequest(delegated, null))
        }
        assertEquals(ReplyTarget.CALLER, delegated.replyTarget)
    }

    @Test
    fun `the channel answers only for chats of its instance`() {
        val foreign = testChat(instance = ChannelInstanceId(ChannelType("other"), "main"))

        assertViolation("a message sent for other:main:chat, a chat of another instance.") {
            channel.send(foreign, reply("x"))
        }
        assertViolation("a question for the capabilities for other:main:chat, a chat of another instance.") {
            channel.capabilities(foreign)
        }
        assertViolation("a question for the parts needed for other:main:chat, a chat of another instance.") {
            channel.partsNeeded(foreign, "x", Markup.PLAIN)
        }
    }

    @Test
    fun `nothing reaches a stopped instance`() {
        val sink = open()
        channel.stopped = true

        assertViolation("a preview of turn test-turn after the instance stopped.") { sink.preview(0, "x") }
        assertViolation("the reply of turn test-turn completed after the instance stopped.") {
            sink.complete(reply("x"))
        }
        assertViolation("a reply of turn other opened after the instance stopped.") {
            channel.openReply(ReplyRequest(turn("other"), null))
        }
        assertViolation("a message sent for test:main:chat after the instance stopped.") {
            channel.send(channel.chat(), reply("x"))
        }
    }

    // What a test receives.

    @Test
    fun `the channel builds chats, users, messages and commands of its instance`() {
        val thread = channel.chat("group", "7")
        val message = channel.message("hi", thread, channel.user("ada")) { text("hi there") }
        val command = channel.command("new", "now", issuer = channel.user("bob"))

        assertEquals("test:main:group#7", thread.toString())
        assertTrue(channel.user("ada").isAdmin)
        assertFalse(channel.user().isAdmin)
        assertEquals("Ada", channel.user("ada").displayName)
        assertEquals(ChannelMessageRef(thread, "in-1"), message.ref)
        assertEquals("hi there", message.text)
        assertNull(message.forwarded)
        assertEquals(TEST_TIME, message.receivedAt)
        assertEquals("new", command.name)
        assertEquals("now", command.arguments)
        assertEquals(ChannelMessageRef(channel.chat(), "in-2"), command.trigger)
        assertEquals("test:main@bob", command.issuer.address.toString())
    }

    @Test
    fun `what the channel receives goes to its submitter`() {
        submitter.refuseNext(RefusalReason.QUEUE_FULL, 20)

        val refused = channel.receive("first")
        val accepted = channel.receiveCommand("new", "now", issuer = channel.user("ada"))

        assertEquals(Admission.Refused(RefusalReason.QUEUE_FULL, 20), refused)
        assertIs<Admission.Accepted>(accepted)
        val (message, command) = submitter.submissions
        assertEquals("first", assertIs<Submission.Message>(message).message.text)
        val carried = assertIs<Submission.Command>(command)
        assertEquals("/new now", carried.message?.text)
        assertEquals(carried.message?.ref, carried.invocation.trigger)
        assertTrue(carried.invocation.issuer.isAdmin)
        assertEquals(
            listOf(RecordingChannel.Event.Received(message), RecordingChannel.Event.Received(command)),
            channel.events,
        )
    }

    @Test
    fun `a channel without a submitter or a submission of another instance is refused`() {
        val alone = RecordingChannel()
        val foreign = Submission.Message(
            testMessage(chat = testChat(instance = ChannelInstanceId(ChannelType("x"), "y"))),
        )

        val missing = assertFailsWith<IllegalStateException> { alone.receive("hi") }

        assertEquals(
            "Recording channel test:main has no TurnSubmitter: run the agent, or give the harness a " +
                "RecordingTurnSubmitter.",
            missing.message,
        )
        assertFailsWith<IllegalArgumentException> { channel.submit(foreign) }
        assertFailsWith<IllegalArgumentException> { RecordingChannel(partLength = 0) }
    }
}
