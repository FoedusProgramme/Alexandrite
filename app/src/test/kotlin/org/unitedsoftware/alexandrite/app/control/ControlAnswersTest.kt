package org.unitedsoftware.alexandrite.app.control

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.logged
import org.unitedsoftware.alexandrite.channel.common.InternalChannelCommonApi
import org.unitedsoftware.alexandrite.channel.common.pairing.PairingDecision
import org.unitedsoftware.alexandrite.channel.common.pairing.Unpairing
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.turn.ChatSettingsUpdate
import org.unitedsoftware.alexandrite.testkit.RecordingAgentControl
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ControlAnswersTest {
    @TempDir
    lateinit var directory: Path

    private val bob = request("42", "BBBB-BBBB", minutes = 2)
    private val group = request("-100", "GGGG-GGGG", minutes = 5, kind = ChatKind.GROUP, title = "Backend")
    private val carol = request("43", "CCCC-CCCC", minutes = 1, requester = null)
    private val first = FakeDesk(bob, paired = listOf(declared("7")))
    private val second =
        FakeDesk(
            group,
            carol,
            paired = listOf(
                declared("8"),
                approved(request("9", "NNNN-NNNN"), UserAddress(WORK, "1"), running = false),
            ),
        )

    // Pairings.

    @Test
    fun `the requests of every desk come oldest first, then the paired chats`() {
        served(directory, first, second) { socket ->
            val pairings = assertIs<ControlReply.Pairings>(ask(socket, ControlRequest.ListPairings))

            assertEquals(listOf("GGGG-GGGG", "BBBB-BBBB", "CCCC-CCCC"), pairings.requests.map { it.code })
            assertEquals(listOf("chat:work:7", "chat:work:8", "chat:work:9"), pairings.paired.map { it.chat })
            assertEquals(listOf(true, true, false), pairings.paired.map { it.declared })
            assertEquals(listOf(true, true, false), pairings.paired.map { it.instanceRunning })
            val approval = checkNotNull(pairings.paired.last().approval)
            assertEquals(
                listOf("direct", null, "chat:work@9", "User 9", "user9", NOW, "chat:work@1"),
                listOf(
                    approval.kind,
                    approval.title,
                    approval.requester?.user,
                    approval.requester?.name,
                    approval.requester?.username,
                    approval.approvedAt,
                    approval.approvedBy,
                ),
            )
            val view = pairings.requests.first()
            assertEquals(
                listOf("chat:work:-100", "group", "Backend", "chat:work@-100", "User -100", "user-100"),
                listOf(view.chat, view.kind, view.title, view.requester?.user, view.requester?.name) +
                    view.requester?.username,
            )
            assertEquals(group.expiresAt, view.expiresAt)
            assertEquals(null, pairings.requests.last().requester)
        }
    }

    // Decisions.

    @Test
    fun `a code is approved or denied by the host`() {
        val desk = FakeDesk(bob, carol)

        served(directory, desk) { socket ->
            val approved = assertIs<ControlReply.Approved>(ask(socket, ControlRequest.Approve("BBBB-BBBB")))
            val denied = assertIs<ControlReply.Denied>(ask(socket, ControlRequest.Deny("CCCC-CCCC")))

            assertEquals("chat:work:42" to "chat:work:43", approved.request.chat to denied.request.chat)
            assertEquals(
                listOf("approve BBBB-BBBB by the host", "deny CCCC-CCCC by the host"),
                desk.calls.filterNot { it.startsWith("holds") },
            )
        }
    }

    @Test
    fun `every decision of a desk reaches the client`() {
        val desk = FakeDesk(bob)

        served(directory, desk) { socket ->
            suspend fun approve() = ask(socket, ControlRequest.Approve("BBBB-BBBB"))

            desk.decision = PairingDecision.Failed(request("42", "NEWC-0DE0"), "The disk is full.")
            val notKept = assertIs<ControlReply.NotKept>(approve())
            assertEquals("NEWC-0DE0" to "The disk is full.", notKept.request.code to notKept.reason)

            desk.decision = PairingDecision.Unknown
            assertEquals(ControlReply.UnknownCode, approve())
            desk.decision = PairingDecision.Malformed
            assertEquals(ControlReply.MalformedCode, approve())
        }
    }

    // Unpairing.

    @Test
    fun `a chat is unpaired by the desk that pairs it, its thread naming the chat`() {
        served(directory, first, second) { socket ->
            val unpaired = ask(socket, ControlRequest.Unpair("chat:work:9#3"))

            assertEquals("chat:work:9", assertIs<ControlReply.Unpaired>(unpaired).chat)
            assertEquals(listOf("unpair chat:work:9"), first.calls)
            assertEquals(listOf("unpair chat:work:9"), second.calls)
            assertEquals(listOf(chat("8")), second.paired.map { it.chat })
            val again = ask(socket, ControlRequest.Unpair("chat:work:9"))
            assertEquals("chat:work:9", assertIs<ControlReply.NotPaired>(again).chat)
        }
    }

    @Test
    fun `an unpairing that the config file keeps or that fails is reported`() {
        served(directory, first) { socket ->
            for (removed in listOf(false, true)) {
                first.unpairing = Unpairing.Declared(approvalRemoved = removed)
                val declared = assertIs<ControlReply.Declared>(ask(socket, ControlRequest.Unpair("chat:work:7")))
                assertEquals("chat:work:7" to removed, declared.chat to declared.approvalRemoved)
            }

            first.unpairing = Unpairing.Failed("The file is read-only.")
            val failed = assertIs<ControlReply.UnpairFailed>(ask(socket, ControlRequest.Unpair("chat:work:7")))
            assertEquals("chat:work:7" to "The file is read-only.", failed.chat to failed.reason)
        }
    }

    @Test
    fun `an unpaired chat that an agent still keeps as its home chat or linked is named in the reply and the log`() {
        val control = RecordingAgentControl()
        val desk = FakeDesk(paired = listOf(approved(request("9", "NNNN-NNNN")), approved(request("10", "MMMM-MMMM"))))
        lateinit var unpaired: List<ControlReply.Unpaired>

        val lines = logged {
            served(directory, desk, control = control) { socket ->
                control.updateSettings(chat("9", "5"), ChatSettingsUpdate.builder().home().build(), null)
                control.link(AgentId.MAIN, chat("7"), chat("9"), null)
                unpaired = listOf("chat:work:9", "chat:work:10").map {
                    assertIs<ControlReply.Unpaired>(ask(socket, ControlRequest.Unpair(it)))
                }
            }
        }

        assertEquals(
            listOf(listOf("main") to listOf("main"), emptyList<String>() to emptyList()),
            unpaired.map { it.homeOf to it.linkedAt },
        )
        assertEquals(
            listOf(
                "WARN Chat chat:work:9 is unpaired but still the home chat of agent main and linked at agent main: " +
                    "the bot sends it nothing until it is paired again",
            ),
            lines.filter { it.startsWith("WARN") },
        )
    }

    @Test
    fun `an answer of a desk that this app does not know is a failure`() {
        val desk = FakeDesk(request("42", "BBBB-BBBB"))
        desk.decision = Expired
        desk.unpairing = Revoked

        served(directory, desk) { socket ->
            assertEquals(
                "A pairing desk answered Expired, which this Alexandrite does not know.",
                assertIs<ControlReply.Failure>(ask(socket, ControlRequest.Approve("BBBB-BBBB"))).message,
            )
            assertEquals(
                "A pairing desk answered Revoked, which this Alexandrite does not know.",
                assertIs<ControlReply.Failure>(ask(socket, ControlRequest.Unpair("chat:work:42"))).message,
            )
        }
    }

    @Test
    fun `an address that names no chat is refused before any desk is asked`() {
        served(directory, first) { socket ->
            val failure = assertIs<ControlReply.Failure>(ask(socket, ControlRequest.Unpair("work 7")))

            assertEquals("'work 7' is no chat address, such as telegram:work:123456789.", failure.message)
            assertEquals(emptyList(), first.calls)
        }
    }

    // No desk.

    @Test
    fun `without a desk no channel takes pairing codes, and a ping is still answered`() {
        served(directory) { socket ->
            val requests = listOf(
                ControlRequest.ListPairings,
                ControlRequest.Approve("BBBB-BBBB"),
                ControlRequest.Deny("BBBB-BBBB"),
                ControlRequest.Unpair("chat:work:7"),
            )

            for (request in requests) assertEquals(ControlReply.NoDesk, ask(socket, request))
            assertIs<ControlReply.Pong>(ask(socket, ControlRequest.Ping))
        }
    }

    @OptIn(InternalChannelCommonApi::class)
    private data object Expired : PairingDecision

    @OptIn(InternalChannelCommonApi::class)
    private data object Revoked : Unpairing
}
