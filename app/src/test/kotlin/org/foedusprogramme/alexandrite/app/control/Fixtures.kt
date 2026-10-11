package org.foedusprogramme.alexandrite.app.control

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.foedusprogramme.alexandrite.channel.common.pairing.PairedChat
import org.foedusprogramme.alexandrite.channel.common.pairing.PairingApproval
import org.foedusprogramme.alexandrite.channel.common.pairing.PairingDecision
import org.foedusprogramme.alexandrite.channel.common.pairing.PairingDesk
import org.foedusprogramme.alexandrite.channel.common.pairing.PairingDesks
import org.foedusprogramme.alexandrite.channel.common.pairing.PairingRequest
import org.foedusprogramme.alexandrite.channel.common.pairing.Unpairing
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

val WORK = ChannelInstanceId(ChannelType("chat"), "work")

val NOW: Instant = Instant.parse("2026-10-11T08:00:00Z")

fun chat(id: String): ChatAddress = ChatAddress(WORK, id)

/** The request of chat [id] to be paired, which got [code] [minutes] minutes before [NOW]. */
fun request(
    id: String,
    code: String,
    minutes: Long = 0,
    kind: ChatKind = ChatKind.DIRECT,
    title: String? = null,
    requester: ChatUser? = ChatUser(UserAddress(WORK, id), "User $id", "user$id", isBot = false, isAdmin = false),
): PairingRequest {
    val issued = NOW.minusSeconds(minutes * 60)
    return PairingRequest(chat(id), kind, title, requester, code, issued, issued.plusSeconds(600))
}

/** The chat [id], which the config file declares paired. */
fun declared(id: String): PairedChat = PairedChat(chat(id), true, null)

/** The chat of [request], paired at [NOW] by [by]. */
fun approved(request: PairingRequest, by: UserAddress? = null): PairedChat =
    PairedChat(request.chat, false, PairingApproval(request.kind, request.title, request.requester, NOW, by))

/** A desk that holds [requests] and pairs [paired], recording what it is asked. */
class FakeDesk(vararg requests: PairingRequest, paired: List<PairedChat> = emptyList()) : PairingDesk {
    val requests = requests.toMutableList()
    val paired = paired.toMutableList()
    val calls = CopyOnWriteArrayList<String>()

    /** What every approval and denial decides instead, unless it is null. */
    var decision: PairingDecision? = null

    /** What every unpairing answers instead, unless it is null. */
    var unpairing: Unpairing? = null

    override fun pending(): List<PairingRequest> = requests.toList()

    override fun holds(code: String): Boolean {
        calls += "holds $code"
        return requests.any { it.code == code }
    }

    override suspend fun approve(code: String): PairingDecision {
        calls += "approve $code"
        decision?.let { return it }
        val request = requests.find { it.code == code } ?: return PairingDecision.Unknown
        requests -= request
        paired += approved(request)
        return PairingDecision.Approved(request)
    }

    override suspend fun deny(code: String): PairingDecision {
        calls += "deny $code"
        decision?.let { return it }
        val request = requests.find { it.code == code } ?: return PairingDecision.Unknown
        requests -= request
        return PairingDecision.Denied(request)
    }

    override fun paired(): List<PairedChat> = paired.toList()

    override suspend fun unpair(chat: ChatAddress): Unpairing {
        calls += "unpair $chat"
        unpairing?.let { return it }
        return if (paired.removeIf { it.chat == chat }) Unpairing.Unpaired else Unpairing.NotPaired
    }
}

/** Runs [block] with the path of a control socket in [directory] that [answers] answer meanwhile. */
internal fun served(directory: Path, answers: ControlAnswers, block: suspend (Path) -> Unit) {
    runBlocking {
        withTimeout(30.seconds) {
            withContext(Dispatchers.IO) { ControlSocket.open(directory.resolve(CONTROL_SOCKET)) }.use { socket ->
                val serving = launch { socket.serve(answers::answer) }
                try {
                    block(socket.path)
                } finally {
                    serving.cancelAndJoin()
                }
            }
        }
    }
}

/** Runs [block] with the path of a control socket in [directory] over [desks]. */
fun served(directory: Path, vararg desks: PairingDesk, block: suspend (Path) -> Unit) =
    served(directory, ControlAnswers("1.2.3") { PairingDesks(desks.toList()) }, block)

internal suspend fun ask(socket: Path, request: ControlRequest): ControlReply =
    withContext(Dispatchers.IO) { askDaemon(socket, request) }

/** What the app at [socket] answers to the line [line], or null when it answers nothing. */
suspend fun answerTo(socket: Path, line: String): String? = withContext(Dispatchers.IO) {
    SocketChannel.open(UnixDomainSocketAddress.of(socket)).use {
        writeLine(it, line)
        it.shutdownOutput()
        readLine(it, 1024 * 1024)?.takeIf(String::isNotEmpty)
    }
}
