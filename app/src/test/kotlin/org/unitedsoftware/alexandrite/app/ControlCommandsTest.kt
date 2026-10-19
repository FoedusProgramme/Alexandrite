package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.control.ControlRequest
import org.unitedsoftware.alexandrite.app.control.FakeDesk
import org.unitedsoftware.alexandrite.app.control.WORK
import org.unitedsoftware.alexandrite.app.control.approved
import org.unitedsoftware.alexandrite.app.control.chat
import org.unitedsoftware.alexandrite.app.control.declared
import org.unitedsoftware.alexandrite.app.control.request
import org.unitedsoftware.alexandrite.app.control.served
import org.unitedsoftware.alexandrite.channel.common.pairing.PairedChat
import org.unitedsoftware.alexandrite.channel.common.pairing.PairingDecision
import org.unitedsoftware.alexandrite.channel.common.pairing.PairingDesk
import org.unitedsoftware.alexandrite.channel.common.pairing.Unpairing
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.ChatSettingsUpdate
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.testkit.RecordingAgent
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class ControlCommandsTest {
    @TempDir
    lateinit var directory: Path

    private val bob = request("42", "BBBB-BBBB", minutes = 2)
    private val group = request("-100", "GGGG-GGGG", minutes = 5, kind = ChatKind.GROUP, title = "Back\u001B[2Jend")
    private val desk = FakeDesk(
        bob,
        group,
        paired = listOf(
            declared("7"),
            approved(request("9", "NNNN-NNNN", requester = null)),
            approved(request("-200", "MMMM-MMMM", kind = ChatKind.GROUP, title = "Ops"), UserAddress(WORK, "1")),
            PairedChat(chat("8"), true, approved(request("8", "PPPP-PPPP", requester = null)).approval),
            approved(request("10", "QQQQ-QQQQ", requester = null), running = false),
        ),
    )

    /** The exit code, standard output and standard error of [request] while [desks] and the host APIs of [agent] serve. */
    private fun printed(
        request: ControlRequest,
        vararg desks: PairingDesk = arrayOf(desk),
        agent: RecordingAgent? = null,
    ): Answered {
        lateinit var result: Answered
        served(directory, *desks, agent = agent) {
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val code = withContext(Dispatchers.IO) {
                control(directory, request, PrintStream(out, true), PrintStream(err, true), ZoneOffset.UTC)
            }
            result = Triple(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
        }
        return result
    }

    private fun refused(message: String) = Triple(1, "", "alexandrite: $message\n")

    @Test
    fun `the requests are listed with their codes, chats, senders and expiries, then the paired chats`() {
        assertEquals(
            Triple(
                0,
                "Waiting to be paired:\n" +
                    "  GGGG-GGGG  $GROUP, expires 08:05:00\n" +
                    "  BBBB-BBBB  $BOB, expires 08:08:00\n" +
                    "Paired:\n" +
                    "  chat:work:7 (declared in the config file)\n" +
                    "  chat:work:9 (direct, from an unknown sender), approved 2026-10-11 08:00 by the host\n" +
                    "  chat:work:-200 (group \"Ops\", from User -200 @user-200, chat:work@-200), approved " +
                    "2026-10-11 08:00 by chat:work@1\n" +
                    "  chat:work:8 (direct, from an unknown sender), declared in the config file and approved " +
                    "2026-10-11 08:00 by the host\n" +
                    "  chat:work:10 (direct, from an unknown sender), approved 2026-10-11 08:00 by the host; " +
                    "chat:work is not running and pairs it again when it runs\n",
                "",
            ),
            printed(ControlRequest.ListPairings),
        )
        assertEquals(
            Triple(0, "No chat waits to be paired.\nNo chat is paired.\n", ""),
            printed(ControlRequest.ListPairings, FakeDesk()),
        )
    }

    @Test
    fun `a code is approved or denied, or the reason why not is printed`() {
        assertEquals(Triple(0, "Paired $BOB.\n", ""), printed(ControlRequest.Approve("BBBB-BBBB")))
        assertEquals(Triple(0, "Denied the request of $GROUP.\n", ""), printed(ControlRequest.Deny("GGGG-GGGG")))
        assertEquals(
            refused("No chat waits with the code AAAA-AAAA."),
            printed(ControlRequest.Approve("AAAA-AAAA")),
        )
        assertEquals(
            refused("hello is no pairing code: a code is 8 letters and digits, such as ABCD-EFGH."),
            printed(ControlRequest.Approve("hello")),
        )
        val failing = FakeDesk(request("43", "CCCC-CCCC"))
        failing.decision = PairingDecision.Failed(request("43", "NEWC-0DE0"), "The disk is full.")
        assertEquals(
            refused("Cannot pair chat:work:43: The disk is full. Its request now waits under the new code NEWC-0DE0."),
            printed(ControlRequest.Approve("CCCC-CCCC"), failing),
        )
    }

    @Test
    fun `a chat is unpaired, or the reason why not is printed`() {
        assertEquals(Triple(0, "Unpaired chat:work:7.\n", ""), printed(ControlRequest.Unpair("chat:work:7")))
        assertEquals(refused("chat:work:7 is not paired."), printed(ControlRequest.Unpair("chat:work:7")))
        desk.unpairing = Unpairing.Declared(approvalRemoved = false)
        assertEquals(
            refused("chat:work:7 stays paired: the config file declares it, so edit the file to unpair it."),
            printed(ControlRequest.Unpair("chat:work:7")),
        )
        desk.unpairing = Unpairing.Declared(approvalRemoved = true)
        assertEquals(
            refused(
                "Removed the approval of chat:work:7, but it stays paired: the config file declares it, so edit the " +
                    "file and restart Alexandrite to unpair it.",
            ),
            printed(ControlRequest.Unpair("chat:work:7")),
        )
        desk.unpairing = Unpairing.Failed("The file is read-only")
        assertEquals(
            refused("Cannot unpair chat:work:7: The file is read-only."),
            printed(ControlRequest.Unpair("chat:work:7")),
        )
    }

    @Test
    fun `unpairing a chat that is still a home chat or linked says that the bot sends it nothing`() {
        val control = RecordingAgent()
        runBlocking {
            control.settings.update(chat("9"), ChatSettingsUpdate.builder().home().build(), null)
            control.links.link(AgentId.MAIN, chat("7"), chat("9"), null)
        }

        assertEquals(
            Triple(
                0,
                "Unpaired chat:work:9. It is still the home chat of agent main and linked at agent main, so the bot " +
                    "sends it nothing until it is paired again.\n",
                "",
            ),
            printed(ControlRequest.Unpair("chat:work:9"), agent = control),
        )
    }

    private companion object {
        const val BOB = "chat:work:42 (direct, from User 42 @user42, chat:work@42)"
        const val GROUP = "chat:work:-100 (group \"Back\\u001B[2Jend\", from User -100 @user-100, chat:work@-100)"
    }
}

private typealias Answered = Triple<Int, String, String>
