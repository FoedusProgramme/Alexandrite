package org.unitedsoftware.alexandrite.app.control

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.channel.common.pairing.Unpairing
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
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
