package org.foedusprogramme.alexandrite.app

import org.foedusprogramme.alexandrite.app.control.ControlReply
import org.foedusprogramme.alexandrite.app.control.ControlRequest
import org.foedusprogramme.alexandrite.app.control.NoDaemonException
import org.foedusprogramme.alexandrite.app.control.RequestView
import org.foedusprogramme.alexandrite.app.control.askDaemon
import org.foedusprogramme.alexandrite.app.control.controlSocket
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Prints how the app that runs with the data root [dataDir] answers [request], and returns the exit code. */
internal fun control(
    dataDir: Path,
    request: ControlRequest,
    out: PrintStream,
    err: PrintStream,
    zone: ZoneId = ZoneId.systemDefault(),
): Int {
    val reply = try {
        askDaemon(controlSocket(dataDir), request)
    } catch (e: NoDaemonException) {
        err.println("alexandrite: ${e.message}")
        return ExitCode.UNAVAILABLE
    } catch (e: IOException) {
        err.println("alexandrite: ${e.message ?: e}")
        return ExitCode.FAILURE
    }
    val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone)
    fun done(text: String): Int {
        out.println(text)
        return ExitCode.OK
    }
    fun refused(text: String): Int {
        err.println("alexandrite: $text")
        return ExitCode.FAILURE
    }
    return when (reply) {
        is ControlReply.Pong -> done("Alexandrite ${shown(reply.version)} is running.")

        is ControlReply.Pairings -> done(pairings(reply, time))

        is ControlReply.Approved -> done("Paired ${described(reply.request)}.")

        is ControlReply.Denied -> done("Denied the request of ${described(reply.request)}.")

        is ControlReply.NotKept -> refused(
            "Cannot pair ${shown(reply.request.chat)}: ${shown(reply.reason).trimEnd('.')}. Its request now waits " +
                "under the new code ${reply.request.code}.",
        )

        ControlReply.UnknownCode -> refused("No chat waits with the code ${shown(codeOf(request))}.")

        ControlReply.MalformedCode -> refused(
            "${shown(codeOf(request))} is no pairing code: a code is 8 letters and digits, such as ABCD-EFGH.",
        )

        is ControlReply.Unpaired -> done("Unpaired ${shown(reply.chat)}.")

        is ControlReply.NotPaired -> refused("${shown(reply.chat)} is not paired.")

        is ControlReply.PairedByReference -> refused(
            "${shown(reply.chat)} stays paired: config entries that name environment variables still pair it.",
        )

        is ControlReply.UnpairFailed ->
            refused("Cannot unpair ${shown(reply.chat)}: ${shown(reply.reason).trimEnd('.')}.")

        ControlReply.NoDesk -> refused("No channel takes pairing codes.")

        is ControlReply.Failure -> refused(shown(reply.message))
    }
}

private fun pairings(reply: ControlReply.Pairings, time: DateTimeFormatter): String = buildString {
    if (reply.requests.isEmpty()) {
        appendLine("No chat waits to be paired.")
    } else {
        appendLine("Waiting to be paired:")
        for (request in reply.requests) {
            appendLine("  ${request.code}  ${described(request)}, expires ${time.format(request.expiresAt)}")
        }
    }
    if (reply.paired.isEmpty()) {
        append("No chat is paired.")
    } else {
        append("Paired:")
        for (chat in reply.paired) append("\n  ${shown(chat)}")
    }
}

/** The chat of [request], its kind and title, and who asked. */
private fun described(request: RequestView): String = buildString {
    append(shown(request.chat)).append(" (").append(shown(request.kind))
    request.title?.let { append(" \"").append(shown(it)).append('"') }
    append(", from ")
    val requester = request.requester
    if (requester == null) {
        append("an unknown sender")
    } else {
        append(shown(requester.name))
        requester.username?.let { append(" @").append(shown(it)) }
        append(", ").append(shown(requester.user))
    }
    append(')')
}

private fun codeOf(request: ControlRequest): String = when (request) {
    is ControlRequest.Approve -> request.code
    is ControlRequest.Deny -> request.code
    else -> ""
}

/** [text] with its control, format and separator characters escaped. */
private fun shown(text: String): String = buildString {
    for (char in text) {
        when (Character.getType(char).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR ->
                append("\\u%04X".format(char.code))

            else -> append(char)
        }
    }
}
