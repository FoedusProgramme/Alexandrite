package org.foedusprogramme.alexandrite.agent.prompt

import org.foedusprogramme.alexandrite.sdk.channel.IncomingMessage
import org.foedusprogramme.alexandrite.sdk.channel.MediaAttachment
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.model.Trust
import org.foedusprogramme.alexandrite.sdk.transcript.ContextPart
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserOrigin
import org.foedusprogramme.alexandrite.sdk.transcript.UserPart
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The source of the framing parts of format v1. */
internal const val MESSAGE_SOURCE: String = "alexandrite.message"

/** The source of the framing part of initiated framing v1. */
internal const val INITIATED_SOURCE: String = "alexandrite.initiated"

/** The source of the note on the attachments that a message's entry leaves out. */
internal const val MEDIA_SOURCE: String = "alexandrite.media"

/** The unstored user entry of [message] with [text] as its words and [carried] between its framing and its words. */
internal fun messageEntry(
    message: IncomingMessage,
    text: String,
    zone: ZoneId,
    linked: Boolean,
    carried: List<UserPart> = emptyList(),
): UserEntry {
    val parts = buildList<UserPart> {
        add(ContextPart(MESSAGE_SOURCE, messageFraming(message, zone, linked)))
        addAll(carried)
        if (text.isNotEmpty()) add(TextPart(Escaper.HEADERS.text(text)))
    }
    val origin = UserOrigin.FromChat(message.sender, message.ref, message.receivedAt, message.forwarded, message.quote)
    return UserEntry(null, parts, origin)
}

/** Message framing v1: who sent [message], when and where, naming the chat's instance where it is [linked]. */
internal fun messageFraming(message: IncomingMessage, zone: ZoneId, linked: Boolean): String = buildString {
    val sender = message.sender
    appendLine("[alexandrite:message]")
    append("From: ").append(oneLine(sender.displayName))
    sender.username?.let { append(" (@").append(oneLine(it)).append(')') }
    appendLine()
    appendLine("Operator: ${if (sender.isAdmin) "yes" else "no"}")
    appendLine("Received: ${TIME.format(message.receivedAt.atZone(zone))}")
    // The id of this message, which is what a tool that acts on it needs and what a person can be given to trace it by:
    // a prompt naming no id left a call about the message just written with nothing to name it with.
    appendLine("Message id: ${oneLine(message.ref.id)}")
    append("Chat: ").append(message.chatInfo.kind)
    message.chatInfo.title?.let { append(" \"").append(oneLine(it)).append('"') }
    if (linked) append(" on ").append(message.chat.instance)
    appendLine()
    message.forwarded?.let { appendLine("Forwarded from: ${oneLine(it.name ?: UNKNOWN)} (${it.kind})") }
    message.quote?.let { quote ->
        val trust = if (quote.trust.live) "verified" else "unverified"
        // The id of the message that was quoted, so that a call can name it: what a message answers is reported as an id
        // and nothing else, and a person quoting a message is asking about that one.
        val quoted = quote.target?.let { " [id ${oneLine(it.id)}]" }.orEmpty()
        appendLine("Quoting: ${oneLine(quote.senderName ?: UNKNOWN)} ($trust)$quoted")
        lines(quote.text).forEach { appendLine(if (it.isEmpty()) ">" else "> $it") }
    }
    message.facts.forEach { appendLine("${factLabel(it.label)}: ${oneLine(it.value)}") }
    append("[/alexandrite:message]")
}

/**
 * The unstored opening entry of a turn that [plugin] started as [kind] at [startedAt], with [text] as its prompt and
 * [carried] between its framing and its prompt.
 */
internal fun initiatedEntry(
    plugin: String,
    kind: TurnKind,
    trust: Trust,
    startedAt: Instant,
    text: String,
    zone: ZoneId,
    carried: List<UserPart> = emptyList(),
): UserEntry {
    val parts = buildList<UserPart> {
        add(ContextPart(INITIATED_SOURCE, initiatedFraming(plugin, kind, trust, startedAt, zone)))
        addAll(carried)
        if (text.isNotEmpty()) add(TextPart(Escaper.HEADERS.text(text)))
    }
    return UserEntry(null, parts, UserOrigin.Initiated(plugin, kind))
}

/** Initiated framing v1: which plugin started a turn, as what, whether its prompt is trusted, and when. */
internal fun initiatedFraming(plugin: String, kind: TurnKind, trust: Trust, startedAt: Instant, zone: ZoneId): String =
    buildString {
        appendLine("[alexandrite:initiated]")
        appendLine("Plugin: ${oneLine(plugin)}")
        appendLine("Kind: ${oneLine(kind.id)}")
        appendLine("Trust: ${if (trust == Trust.TRUSTED) "trusted" else "untrusted"}")
        appendLine("Started: ${TIME.format(startedAt.atZone(zone))}")
        append("[/alexandrite:initiated]")
    }

/** The note on the attachments that a message's entry leaves out, each with why. */
internal fun mediaNote(leftOut: List<Pair<MediaAttachment, String>>): ContextPart = ContextPart(
    MEDIA_SOURCE,
    buildString {
        appendLine("[alexandrite:media]")
        for ((attachment, why) in leftOut) {
            append("Left out: ").append(attachment.kind)
            attachment.name?.let { append(" \"").append(oneLine(it)).append('"') }
            appendLine(" (${oneLine(attachment.mediaType)}), $why")
        }
        append("[/alexandrite:media]")
    },
)

/** [label] on one line, with a backslash in front when it would pass for a framing field, a quote or a header. */
private fun factLabel(label: String): String {
    val line = Escaper.HEADERS.text(oneLine(label))
    val seen = line.replace(INVISIBLE_RUN, "").lowercase(Locale.ROOT)
    val forged = seen.startsWith(">") || seen.split(COLON, limit = 2).first() in FRAMING_FIELDS
    return if (forged && !line.startsWith("\\")) "\\$line" else line
}

private const val UNKNOWN = "unknown"

/** The fields of message framing v1, as a label shows them without its invisible characters. */
private val FRAMING_FIELDS = setOf("from", "operator", "received", "chat", "forwardedfrom", "quoting")

private val INVISIBLE_RUN = Regex("$INVISIBLE+")

/** A colon and the characters that look like one. */
private val COLON = Regex("[:\\u02F8\\u05C3\\u2236\\uA789\\uFE13\\uFE55\\uFF1A]")

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mmXXX", Locale.ROOT)
