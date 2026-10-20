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
    texts: CliTexts = CliTexts.ENGLISH,
): Int {
    val reply = try {
        askDaemon(controlSocket(dataDir), request)
    } catch (e: NoDaemonException) {
        err.println("alexandrite: ${texts(e.text)}")
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
    val t = texts
    return when (reply) {
        is ControlReply.Pong -> done(t("control.pong", "version" to shown(reply.version)))

        is ControlReply.Pairings -> done(pairings(t, reply, time, day))

        is ControlReply.Approved -> done(t("pairing.approved", "chat" to described(t, reply.request)))

        is ControlReply.Denied -> done(t("pairing.denied", "chat" to described(t, reply.request)))

        is ControlReply.NotKept -> refused(
            t(
                "pairing.not_kept",
                "chat" to shown(reply.request.chat),
                "reason" to shown(reply.reason).trimEnd('.'),
                "code" to reply.request.code,
            ),
        )

        ControlReply.UnknownCode -> refused(t("pairing.unknown_code", "code" to shown(codeOf(request))))

        ControlReply.MalformedCode -> refused(t("pairing.malformed_code", "code" to shown(codeOf(request))))

        is ControlReply.Unpaired -> done(t("pairing.unpaired", "chat" to shown(reply.chat)) + stillUsed(t, reply))

        is ControlReply.NotPaired -> refused(t("pairing.not_paired", "chat" to shown(reply.chat)))

        is ControlReply.Declared -> refused(
            t(
                if (reply.approvalRemoved) "pairing.declared_approval_removed" else "pairing.declared",
                "chat" to shown(reply.chat),
            ),
        )

        is ControlReply.UnpairFailed -> refused(
            t("pairing.unpair_failed", "chat" to shown(reply.chat), "reason" to shown(reply.reason).trimEnd('.')),
        )

        ControlReply.NoDesk -> refused(t("pairing.no_desk"))

        is ControlReply.Failure -> refused(shown(reply.message))

        else -> refused(t("unexpected_answer"))
    }
}

/** Prints the waiting and the paired chats, as JSON when [json]. */
internal fun pairings(cli: Cli, json: Boolean): Int {
    if (!json) {
        return control(cli.locations.dataDir, ControlRequest.ListPairings, cli.out, cli.err, texts = cli.texts)
    }
    val reply = asked(cli, ControlRequest.ListPairings) ?: return ExitCode.UNAVAILABLE
    if (reply !is ControlReply.Pairings) return answered(cli, reply)
    cli.printJson(
        buildJsonObject {
            put("waiting", OUTPUT_JSON.encodeToJsonElement(ListSerializer(RequestView.serializer()), reply.requests))
            put("paired", OUTPUT_JSON.encodeToJsonElement(ListSerializer(PairedView.serializer()), reply.paired))
        },
    )
    return ExitCode.OK
}

/** Prints the paired chats and the agent that serves each, as JSON when [json]. */
internal fun chats(cli: Cli, json: Boolean): Int {
    val reply = asked(cli, ControlRequest.Chats) ?: return ExitCode.UNAVAILABLE
    if (reply !is ControlReply.Chats) return answered(cli, reply)
    if (json) {
        cli.printJson(OUTPUT_JSON.encodeToJsonElement(ListSerializer(ChatView.serializer()), reply.chats))
        return ExitCode.OK
    }
    if (reply.chats.isEmpty()) return cli.done(cli.t("pairing.none_paired"))
    for (chat in reply.chats) {
        val about = listOfNotNull(
            chat.kind?.let { kind -> chat.title?.let { "$kind \"${shown(it)}\"" } ?: kind },
            chat.agent?.let { cli.t(if (chat.home) "chats.agent_home" else "chats.agent", "agent" to it) },
            chat.model?.let { cli.t("chats.model", "model" to it) },
        )
        cli.out.println("${shown(chat.chat)}  ${about.joinToString(", ").ifEmpty { cli.t("chats.declared") }}")
    }
    return ExitCode.OK
}

private fun asked(cli: Cli, request: ControlRequest): ControlReply? =
    cli.askOrFail(request, cli.t("control.not_running", "directory" to controlSocket(cli.locations.dataDir).parent))

private fun answered(cli: Cli, reply: ControlReply): Int = when (reply) {
    ControlReply.NoDesk -> cli.fail(cli.t("pairing.no_desk"))
    is ControlReply.Failure -> cli.fail(shown(reply.message))
    else -> cli.fail(cli.t("unexpected_answer"))
}

private val OUTPUT_JSON = Json { encodeDefaults = true }

private fun pairings(
    t: CliTexts,
    reply: ControlReply.Pairings,
    time: DateTimeFormatter,
    day: DateTimeFormatter,
): String = buildString {
    if (reply.requests.isEmpty()) {
        appendLine(t("pairing.none_waiting"))
    } else {
        appendLine(t("pairing.waiting"))
        for (request in reply.requests) {
            val expires = time.format(request.expiresAt)
            appendLine(
                "  ${request.code}  ${t("pairing.request", "chat" to described(t, request), "expires" to expires)}",
            )
        }
    }
    if (reply.paired.isEmpty()) {
        append(t("pairing.none_paired"))
    } else {
        append(t("pairing.paired"))
        for (paired in reply.paired) append("\n  ").append(described(t, paired, day))
    }
}

/** The paired chat [paired] and how it was paired. */
private fun described(t: CliTexts, paired: PairedView, day: DateTimeFormatter): String {
    val approval = paired.approval ?: return t("pairing.declared_chat", "chat" to shown(paired.chat))
    val by = approval.approvedBy?.let(::shown) ?: t("pairing.the_host")
    val approved = t("pairing.approved_at", "day" to day.format(approval.approvedAt), "by" to by)
    val how = when {
        paired.declared -> t("pairing.declared_and", "approved" to approved)

        !paired.instanceRunning -> {
            val instance = ChatAddress.parseOrNull(paired.chat)?.instance?.let { shown("$it") }
                ?: t("pairing.its_instance")
            t("pairing.not_running", "approved" to approved, "instance" to instance)
        }

        else -> approved
    }
    return "${shown(paired.chat)} (${chatOf(t, approval.kind, approval.title, approval.requester)}), $how"
}

/** The note that the chat of [reply] is still a home chat or linked, empty when it is neither. */
private fun stillUsed(t: CliTexts, reply: ControlReply.Unpaired): String {
    val uses = reply.homeOf.map { t("pairing.home_of", "agent" to shown(it)) } +
        reply.linkedAt.map { t("pairing.linked_at", "agent" to shown(it)) }
    if (uses.isEmpty()) return ""
    return t("pairing.still_used", "uses" to uses.joinToString(t("pairing.and")))
}

/** The chat of [request], its kind and title, and who asked. */
private fun described(t: CliTexts, request: RequestView): String =
    "${shown(request.chat)} (${chatOf(t, request.kind, request.title, request.requester)})"

/** A chat's [kind] and [title], and [requester]. */
private fun chatOf(t: CliTexts, kind: String, title: String?, requester: RequesterView?): String = buildString {
    append(shown(kind))
    title?.let { append(" \"").append(shown(it)).append('"') }
    append(t("pairing.from"))
    if (requester == null) {
        append(t("pairing.unknown_sender"))
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
