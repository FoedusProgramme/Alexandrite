package org.unitedsoftware.alexandrite.testkit

import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalRequest
import org.unitedsoftware.alexandrite.sdk.channel.ChannelCapabilities
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.channel.ReplyEnd
import org.unitedsoftware.alexandrite.sdk.channel.ReplyRequest
import org.unitedsoftware.alexandrite.sdk.channel.ReplySink
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.Submission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RecordingChannelTest {
    private val submitter = RecordingTurnSubmitter()
    private val channel = RecordingChannel(operators = setOf("ada"), partLength = 10, submitter = submitter)
    private val turn = testTurn()

    private fun turn(
        id: String,
        block: TurnInfo.Builder.() -> Unit = {
        },
    ): TurnInfo = testTurn(id = TurnId(id), block = block)

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
    fun `a delivery is one message per part unless it is scripted, and too long beyond the chat's parts`() {
        val chat = channel.chat("busy")
        val gone = Delivery.NotDelivered(DeliveryFailure.CHAT_GONE, "left")
        val limited = Delivery.NotDelivered(DeliveryFailure.RATE_LIMITED, retryAfter = 2.seconds)
        channel.scriptDeliveries(gone, chat = chat)
        channel.scriptDeliveries(limited)
        channel.scriptCapabilities(ChannelCapabilities.builder().maxPartsPerReply(2).build(), turn.chat)

        val deliveries = blocking {
            listOf(
                channel.send(chat, reply("first")),
                channel.send(channel.chat("calm"), reply("second")),
                channel.send(chat, reply("third")),
                channel.send(chat, reply("a text of 25 characters..")),
            )
        }
        val delivery = blocking { open().complete(reply("a text of 25 characters..")) }

        assertEquals(gone, deliveries[0])
        assertEquals(limited, deliveries[1])
        assertEquals(Delivery.Delivered(listOf(ChannelMessageRef(chat, "out-1"))), deliveries[2])
        assertEquals(3, (deliveries[3] as Delivery.Delivered).messages.size)
        assertEquals(3, blocking { channel.partsNeeded(chat, "a text of 25 characters..", Markup.PLAIN) })
        assertEquals(1, blocking { channel.partsNeeded(chat, "", Markup.MARKDOWN) })
        val failure = assertIs<Delivery.NotDelivered>(delivery)
        assertEquals(DeliveryFailure.TOO_LONG, failure.kind)
        assertEquals(delivery, channel.reply(turn.id).delivery)
    }

    // The rules of Channel and ReplySink.

    private fun assertViolation(expected: String, call: suspend () -> Unit) {
        val before = channel.events
        val error = assertFailsWith<IllegalStateException> { blocking { call() } }
        assertEquals("Recording channel test:main saw $expected", error.message)
        assertEquals(expected, channel.violations.last().removePrefix("Recording channel test:main saw "))
        assertEquals(before, channel.events)
    }

    @Test
    fun `a reply sink is used in order and ends once, by a turn of the instance, for chats of the instance`() {
        val completed = open(turn("completed"))
        val abandoned = open(turn("abandoned"))
        val streaming = open(turn("streaming"))
        open()
        blocking {
            completed.complete(reply("Done"))
            abandoned.abandon(ReplyEnd.FAILED)
            streaming.preview(1, "Second")
        }
        val elsewhere = testTurn(chat = testChat(instance = ChannelInstanceId(ChannelType("test"), "work")))
        val delegated = testDelegatedTurn(turn)
        val foreign = testChat(instance = ChannelInstanceId(ChannelType("other"), "main"))
        val other = "other:main:chat, a chat of another instance."

        fun case(expected: String, call: suspend () -> Unit) = expected to call
        val violations = listOf(
            case("a preview of turn completed after its reply ended.") { completed.preview(0, "late") },
            case("the reply of turn completed completed after it ended.") { completed.complete(reply("again")) },
            case("the reply of turn completed abandoned after it ended.") { completed.abandon(ReplyEnd.FAILED) },
            case("the reply of turn abandoned completed after it ended.") { abandoned.complete(reply("late")) },
            case("a preview of turn streaming in segment 0 after segment 1 froze it.") {
                streaming.preview(0, "First")
            },
            case("a preview of turn streaming in segment -1.") { streaming.preview(-1, "Before") },
            case("a second reply of turn test-turn.") { channel.openReply(ReplyRequest(turn, null)) },
            case("a reply of turn test-turn opened for test:work:chat.") {
                channel.openReply(ReplyRequest(elsewhere, null))
            },
            case("a reply of turn test-run-turn, which replies to its caller.") {
                channel.openReply(ReplyRequest(delegated, null))
            },
            case("a message sent for $other") { channel.send(foreign, reply("x")) },
            case("a question for the capabilities for $other") { channel.capabilities(foreign) },
            case("a question for the parts needed for $other") { channel.partsNeeded(foreign, "x", Markup.PLAIN) },
            case("an approval request for $other") { channel.askApproval(approval("a1", testTurn(chat = foreign))) },
        )

        for ((expected, call) in violations) assertViolation(expected, call)
        assertEquals(violations.size, channel.violations.size)
    }

    @Test
    fun `a reply records the segments the agent starts in order while it is open, apart from the events`() {
        val sink = open()

        blocking {
            sink.preview(0, "Looking")
            sink.startSegment(1)
            sink.startSegment(1)
            sink.startSegment(2)
            sink.preview(2, "Found")
            sink.complete(reply("Found"))
        }

        val recorded = channel.reply(turn.id)
        assertEquals(listOf(1, 1, 2), recorded.startedSegments)
        assertEquals(mapOf(0 to "Looking", 2 to "Found"), recorded.segments)
        assertEquals(
            listOf("Opened", "Previewed", "Previewed", "Completed"),
            channel.events.map { it::class.simpleName },
        )
        assertEquals(emptyList(), channel.violations)
        val started = open(turn("started"))
        val ended = open(turn("ended"))
        blocking {
            started.startSegment(2)
            ended.abandon(ReplyEnd.CANCELLED)
        }
        assertViolation("segment 1 of turn started started after segment 2.") { started.startSegment(1) }
        assertViolation("a preview of turn started in segment 1 after segment 2 froze it.") {
            started.preview(1, "Late")
        }
        assertViolation("segment 1 of turn ended started after its reply ended.") { ended.startSegment(1) }
        assertEquals(listOf(2), channel.reply(TurnId("started")).startedSegments)
        assertEquals(emptyList(), channel.reply(TurnId("ended")).startedSegments)
    }

    // Approvals.

    @Test
    fun `approval requests take the scripted answers in order, then the default one, and are recorded`() {
        val ada = channel.user("ada")
        channel.scriptApprovals(ApprovalAnswer.Allowed(ada, always = true), ApprovalAnswer.Denied(ada))

        val answers = blocking { listOf("a1", "a2", "a3").map { channel.askApproval(approval(it)) } }
        channel.defaultApproval = ApprovalAnswer.Expired
        val later = blocking { channel.askApproval(approval("a4")) }

        assertEquals(
            listOf(ApprovalAnswer.Allowed(ada, true), ApprovalAnswer.Denied(ada), RecordingChannel.NO_APPROVER),
            answers,
        )
        assertEquals(ApprovalAnswer.Expired, later)
        assertEquals(listOf("a1", "a2", "a3", "a4"), channel.approvals.map { it.id })
        assertEquals(
            listOf(
                RecordingChannel.Event.ApprovalAsked(approval("a1")),
                RecordingChannel.Event.ApprovalAnswered("a1", ApprovalAnswer.Allowed(ada, true)),
            ),
            channel.events.take(2),
        )
    }

    private fun approval(id: String, turn: TurnInfo = this.turn) =
        ApprovalRequest(id, turn, "fs.write", ToolRisk.WORKSPACE_WRITE, "{}", null, "a test")

    @Test
    fun `a stopped instance takes the agent's calls, ignores previews, delivers nothing and sees broken rules`() {
        val sink = open()
        blocking { sink.preview(0, "Hel") }
        channel.stopped = true

        val (completed, sent) = blocking {
            sink.preview(0, "Hello")
            sink.startSegment(1)
            val completed = sink.complete(reply("Hello"))
            channel.openReply(ReplyRequest(turn("other"), null)).abandon(ReplyEnd.SHUTDOWN)
            completed to channel.send(channel.chat(), reply("x"))
        }

        val recorded = channel.reply(turn.id)
        assertEquals(listOf(RecordingChannel.STOPPED, RecordingChannel.STOPPED), listOf(completed, sent))
        assertEquals(RecordingChannel.STOPPED, recorded.delivery)
        assertEquals(listOf(RecordedReply.Preview(0, "Hel")), recorded.previews)
        assertEquals(emptyList(), recorded.startedSegments)
        assertEquals(ReplyEnd.SHUTDOWN, channel.reply(TurnId("other")).abandoned)
        assertEquals(RecordingChannel.DEFAULT_CAPABILITIES, blocking { channel.capabilities(channel.chat()) })
        assertEquals(emptyList(), channel.violations)
        assertViolation("the reply of turn test-turn completed after it ended.") { sink.complete(reply("x")) }
        assertViolation("a second reply of turn test-turn.") { channel.openReply(ReplyRequest(turn, null)) }
    }

    // What a test receives.

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
        assertTrue(carried.invocation.issuer.isOperator)
        assertEquals(
            listOf(RecordingChannel.Event.Received(message), RecordingChannel.Event.Received(command)),
            channel.events,
        )
    }
}
