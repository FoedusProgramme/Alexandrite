package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.unitedsoftware.alexandrite.app.control.ChatView
import org.unitedsoftware.alexandrite.app.control.ControlReply
import org.unitedsoftware.alexandrite.app.control.ControlRequest
import org.unitedsoftware.alexandrite.app.control.NoDaemonException
import org.unitedsoftware.alexandrite.app.control.PairedView
import org.unitedsoftware.alexandrite.app.control.RequestView
import org.unitedsoftware.alexandrite.app.control.RequesterView
import org.unitedsoftware.alexandrite.app.control.askDaemon
import org.unitedsoftware.alexandrite.app.control.controlSocket
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
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
    val day = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
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

        is ControlReply.Pairings -> done(pairings(reply, time, day))

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

        is ControlReply.Unpaired -> done("Unpaired ${shown(reply.chat)}.${stillUsed(reply)}")

        is ControlReply.NotPaired -> refused("${shown(reply.chat)} is not paired.")

        is ControlReply.Declared -> refused(
            if (reply.approvalRemoved) {
                "Removed the approval of ${shown(reply.chat)}, but it stays paired: the config file declares it, so " +
                    "edit the file and restart Alexandrite to unpair it."
            } else {
                "${shown(reply.chat)} stays paired: the config file declares it, so edit the file to unpair it."
            },
        )

        is ControlReply.UnpairFailed ->
            refused("Cannot unpair ${shown(reply.chat)}: ${shown(reply.reason).trimEnd('.')}.")

        ControlReply.NoDesk -> refused("No channel takes pairing codes.")

        is ControlReply.Failure -> refused(shown(reply.message))

        else -> refused("The running Alexandrite gave an unexpected answer.")
    }
}

/** Prints the waiting and the paired chats, as JSON when [json]. */
internal fun pairings(cli: Cli, json: Boolean): Int {
    if (!json) return control(cli.locations.dataDir, ControlRequest.ListPairings, cli.out, cli.err)
    val reply = asked(cli, ControlRequest.ListPairings) ?: return ExitCode.UNAVAILABLE
    if (reply !is ControlReply.Pairings) return answered(cli, reply)
    cli.printJson(
        buildJsonObject {
            put("waiting", PROTOCOL.encodeToJsonElement(ListSerializer(RequestView.serializer()), reply.requests))
            put("paired", PROTOCOL.encodeToJsonElement(ListSerializer(PairedView.serializer()), reply.paired))
        },
    )
    return ExitCode.OK
}

/** Prints the paired chats and the agent that serves each, as JSON when [json]. */
internal fun chats(cli: Cli, json: Boolean): Int {
    val reply = asked(cli, ControlRequest.Chats) ?: return ExitCode.UNAVAILABLE
    if (reply !is ControlReply.Chats) return answered(cli, reply)
    if (json) {
        cli.printJson(PROTOCOL.encodeToJsonElement(ListSerializer(ChatView.serializer()), reply.chats))
        return ExitCode.OK
    }
    if (reply.chats.isEmpty()) return cli.done("No chat is paired.")
    for (chat in reply.chats) {
        val about = listOfNotNull(
            chat.kind?.let { kind -> chat.title?.let { "$kind \"${shown(it)}\"" } ?: kind },
            chat.agent?.let { "agent $it" + if (chat.home) " (its home)" else "" },
            chat.model?.let { "model $it" },
        )
        cli.out.println("${shown(chat.chat)}  ${about.joinToString(", ").ifEmpty { "declared in the config file" }}")
    }
    return ExitCode.OK
}

/** The running app's answer to [request], null after printing that none runs or why it failed. */
private fun asked(cli: Cli, request: ControlRequest): ControlReply? = try {
    cli.ask(request) ?: run {
        val socket = controlSocket(cli.locations.dataDir)
        cli.fail("No Alexandrite runs with the data directory ${socket.parent}.", ExitCode.UNAVAILABLE)
        null
    }
} catch (e: IOException) {
    cli.fail(e.message ?: "$e")
    null
}

private fun answered(cli: Cli, reply: ControlReply): Int = when (reply) {
    ControlReply.NoDesk -> cli.fail("No channel takes pairing codes.")
    is ControlReply.Failure -> cli.fail(shown(reply.message))
    else -> cli.fail("The running Alexandrite gave an unexpected answer.")
}

private val PROTOCOL = Json { encodeDefaults = true }

private fun pairings(reply: ControlReply.Pairings, time: DateTimeFormatter, day: DateTimeFormatter): String =
    buildString {
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
            for (paired in reply.paired) append("\n  ").append(described(paired, day))
        }
    }

/** The paired chat [paired] and how it was paired. */
private fun described(paired: PairedView, day: DateTimeFormatter): String {
    val approval = paired.approval ?: return "${shown(paired.chat)} (declared in the config file)"
    val approved = "approved ${day.format(approval.approvedAt)} by ${approval.approvedBy?.let(::shown) ?: "the host"}"
    val how = when {
        paired.declared -> "declared in the config file and $approved"

        !paired.instanceRunning -> {
            val instance = ChatAddress.parseOrNull(paired.chat)?.instance?.let { shown("$it") } ?: "its instance"
            "$approved; $instance is not running and pairs it again when it runs"
        }

        else -> approved
    }
    return "${shown(paired.chat)} (${chatOf(approval.kind, approval.title, approval.requester)}), $how"
}

/** The note that the chat of [reply] is still a home chat or linked, empty when it is neither. */
private fun stillUsed(reply: ControlReply.Unpaired): String {
    val uses = reply.homeOf.map { "the home chat of agent ${shown(it)}" } +
        reply.linkedAt.map { "linked at agent ${shown(it)}" }
    if (uses.isEmpty()) return ""
    return " It is still ${uses.joinToString(" and ")}, so the bot sends it nothing until it is paired again."
}

/** The chat of [request], its kind and title, and who asked. */
private fun described(request: RequestView): String =
    "${shown(request.chat)} (${chatOf(request.kind, request.title, request.requester)})"

/** A chat's [kind] and [title], and [requester]. */
private fun chatOf(kind: String, title: String?, requester: RequesterView?): String = buildString {
    append(shown(kind))
    title?.let { append(" \"").append(shown(it)).append('"') }
    append(", from ")
    if (requester == null) {
        append("an unknown sender")
    } else {
        append(shown(requester.name))
        requester.username?.let { append(" @").append(shown(it)) }
        append(", ").append(shown(requester.user))
    }
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
