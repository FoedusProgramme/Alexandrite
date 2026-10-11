package org.foedusprogramme.alexandrite.app.control

import org.foedusprogramme.alexandrite.channel.common.pairing.PairingDecision
import org.foedusprogramme.alexandrite.channel.common.pairing.Unpairing
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.junit.jupiter.api.io.TempDir
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
    private val first = FakeDesk(bob, paired = listOf(chat("7")))
    private val second = FakeDesk(group, carol, paired = listOf(chat("8"), chat("9")))

    // Pairings.

    @Test
    fun `the requests of every desk come oldest first, then the paired chats`() {
        served(directory, first, second) { socket ->
            val pairings = assertIs<ControlReply.Pairings>(ask(socket, ControlRequest.ListPairings))

            assertEquals(listOf("GGGG-GGGG", "BBBB-BBBB", "CCCC-CCCC"), pairings.requests.map { it.code })
            assertEquals(listOf("chat:work:7", "chat:work:8", "chat:work:9"), pairings.paired)
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
    fun `a code goes to the desk that holds it, and no other desk decides on it`() {
        served(directory, first, second) { socket ->
            val approved = assertIs<ControlReply.Approved>(ask(socket, ControlRequest.Approve("CCCC-CCCC")))
            val denied = assertIs<ControlReply.Denied>(ask(socket, ControlRequest.Deny("GGGG-GGGG")))

            assertEquals("chat:work:43" to "chat:work:-100", approved.request.chat to denied.request.chat)
            assertEquals(listOf("holds CCCC-CCCC", "holds GGGG-GGGG"), first.calls)
            assertEquals(
                listOf("holds CCCC-CCCC", "approve CCCC-CCCC", "holds GGGG-GGGG", "deny GGGG-GGGG"),
                second.calls,
            )
            assertEquals(listOf(chat("8"), chat("9"), chat("43")), second.paired)
        }
    }

    @Test
    fun `a code that no desk holds is unknown or malformed at once, and no desk decides on it`() {
        served(directory, first, second) { socket ->
            assertEquals(ControlReply.UnknownCode, ask(socket, ControlRequest.Deny("AAAA-AAAA")))
            assertEquals(ControlReply.MalformedCode, ask(socket, ControlRequest.Approve("hello")))

            assertEquals(listOf("holds AAAA-AAAA", "holds hello"), first.calls)
            assertEquals(listOf("holds AAAA-AAAA", "holds hello"), second.calls)
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
            assertEquals(listOf(chat("8")), second.paired)
            val again = ask(socket, ControlRequest.Unpair("chat:work:9"))
            assertEquals("chat:work:9", assertIs<ControlReply.NotPaired>(again).chat)
        }
    }

    @Test
    fun `an unpairing that a reference keeps or that fails is reported`() {
        served(directory, first) { socket ->
            first.unpairing = Unpairing.ByReference
            assertIs<ControlReply.PairedByReference>(ask(socket, ControlRequest.Unpair("chat:work:7")))

            first.unpairing = Unpairing.Failed("The file is read-only.")
            val failed = assertIs<ControlReply.UnpairFailed>(ask(socket, ControlRequest.Unpair("chat:work:7")))
            assertEquals("chat:work:7" to "The file is read-only.", failed.chat to failed.reason)
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
}
