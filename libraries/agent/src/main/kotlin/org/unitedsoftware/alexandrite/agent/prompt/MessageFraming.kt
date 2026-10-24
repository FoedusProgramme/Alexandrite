package org.unitedsoftware.alexandrite.agent.prompt

import org.unitedsoftware.alexandrite.internal.text.cutWithEllipsis
import org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage
import org.unitedsoftware.alexandrite.sdk.channel.MediaAttachment
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.sdk.transcript.UserPart
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The source of the framing parts of format v1. */
internal const val MESSAGE_SOURCE: String = "alexandrite.message"

/** The source of the framing part of initiated framing v1. */
internal const val INITIATED_SOURCE: String = "alexandrite.initiated"

/** The source of the note that names where a message's attachments were saved. */
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

/**
 * Message framing v1: who sent [message], when, which message it is and where, naming the chat's instance where it is
 * [linked].
 */
internal fun messageFraming(message: IncomingMessage, zone: ZoneId, linked: Boolean): String = buildString {
    val sender = message.sender
    appendLine("[alexandrite:message]")
    append("From: ").append(oneLine(sender.displayName))
    sender.username?.let { append(" (@").append(oneLine(it)).append(')') }
    appendLine()
    appendLine("Operator: ${if (sender.isOperator) "yes" else "no"}")
    appendLine("Received: ${TIME.format(message.receivedAt.atZone(zone))}")
    appendLine("Message: ${oneLine(message.ref.id)}")
    append("Chat: ").append(message.chatInfo.kind)
    message.chatInfo.title?.let { append(" \"").append(oneLine(it)).append('"') }
    if (linked) append(" on ").append(message.chat.instance)
    appendLine()
    message.forwarded?.let { appendLine("Forwarded from: ${oneLine(it.name ?: UNKNOWN)} (${it.kind})") }
    message.quote?.let { quote ->
        val trust = if (quote.trust.live) "verified" else "unverified"
        val target = quote.target?.takeIf { !quote.external && it.chat == message.chat }
        val which = target?.let { ", message ${oneLine(it.id)}" }.orEmpty()
        appendLine("Quoting: ${oneLine(quote.senderName ?: UNKNOWN)} ($trust$which)")
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
    actor: ChatUser? = null,
    details: List<String> = emptyList(),
): UserEntry {
    val parts = buildList<UserPart> {
        add(ContextPart(INITIATED_SOURCE, initiatedFraming(plugin, kind, trust, startedAt, zone, actor, details)))
        addAll(carried)
        if (text.isNotEmpty()) add(TextPart(Escaper.HEADERS.text(text)))
    }
    return UserEntry(null, parts, UserOrigin.Initiated(plugin, kind))
}

/**
 * Initiated framing v1: which plugin started a turn, as what, whether its prompt is trusted, when, the person it runs
 * on behalf of, a line only a mandated turn or an agent message has, and the [details] of its kind.
 */
internal fun initiatedFraming(
    plugin: String,
    kind: TurnKind,
    trust: Trust,
    startedAt: Instant,
    zone: ZoneId,
    actor: ChatUser? = null,
    details: List<String> = emptyList(),
): String = buildString {
    appendLine("[alexandrite:initiated]")
    appendLine("Plugin: ${oneLine(plugin)}")
    appendLine("Kind: ${oneLine(kind.id)}")
    appendLine("Trust: ${if (trust == Trust.TRUSTED) "trusted" else "untrusted"}")
    actor?.let {
        appendLine("On behalf of: ${oneLine(it.displayName)} (${if (it.isOperator) "operator" else "guest"})")
    }
    details.forEach { appendLine(oneLine(it)) }
    appendLine("Started: ${TIME.format(startedAt.atZone(zone))}")
    append("[/alexandrite:initiated]")
}

/** One attachment of a message as the media note names it. */
internal class NotedMedia(
    val attachment: MediaAttachment,
    /** In bytes, null when unknown. */
    val size: Long?,
    /** Where it was saved, null when it was not. */
    val saved: Path?,
    /** Why it was not saved, null when it was. */
    val why: String?,
    /** How the attachment reaches the model, null where it was not saved. */
    val view: View?,
) {
    internal sealed interface View {
        /** The bytes reach the model inline. */
        data object Shown : View

        /** [model] reads the attachment; its reading follows in the message. */
        class Read(val model: ModelRef) : View

        /** Only the file is saved, for [reason], in lower case without a closing full stop. */
        class Saved(val reason: String) : View
    }
}

/** The note that names where each of [noted] was saved or why it was not, and how each reaches the model. */
internal fun mediaNote(noted: List<NotedMedia>): ContextPart = ContextPart(
    MEDIA_SOURCE,
    buildString {
        appendLine("[alexandrite:media]")
        for (media in noted) appendLine(mediaLine(media))
        append("[/alexandrite:media]")
    },
)

private fun mediaLine(media: NotedMedia): String = buildString {
    val attachment = media.attachment
    val saved = media.saved
    if (saved != null) append("Saved to ").append(saved).append(": ") else append("Not saved: ")
    append(attachment.kind)
    attachment.name?.let { append(" \"").append(shortened(oneLine(it))).append('"') }
    append(" (").append(oneLine(attachment.mediaType))
    media.size?.let { append(", ").append(it).append(" bytes") }
    append(')')
    if (attachment.fromQuote) append(", from the quoted message")
    media.why?.let { append(": ").append(oneLine(it)) }
    when (val view = media.view) {
        NotedMedia.View.Shown -> append("; shown to you")
        is NotedMedia.View.Read -> append("; read for you by ${view.model} (its reading follows in the message)")
        is NotedMedia.View.Saved -> append("; not shown: ${oneLine(view.reason)}")
        null -> Unit
    }
}

internal fun shortened(name: String): String = cutWithEllipsis(name, MAX_NAME_CHARS)

/** [label] on one line, with a backslash in front when it would pass for a framing field, a quote or a header. */
private fun factLabel(label: String): String {
    val line = Escaper.HEADERS.text(oneLine(label))
    val seen = line.replace(INVISIBLE_RUN, "").lowercase(Locale.ROOT)
    val forged = seen.startsWith(">") || seen.split(COLON, limit = 2).first() in FRAMING_FIELDS
    return if (forged && !line.startsWith("\\")) "\\$line" else line
}

private const val UNKNOWN = "unknown"

/** The characters of an attachment's name that the media note shows. */
internal const val MAX_NAME_CHARS = 200

/** The fields of message framing v1, as a label shows them without its invisible characters. */
private val FRAMING_FIELDS = setOf("from", "operator", "received", "message", "chat", "forwardedfrom", "quoting")

private val INVISIBLE_RUN = Regex("$INVISIBLE+")

/** A colon and the characters that look like one. */
private val COLON = Regex("[:\\u02F8\\u05C3\\u2236\\uA789\\uFE13\\uFE55\\uFF1A]")

/** A time with its weekday, which models get wrong when they work it out themselves. */
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mmXXX (EEEE)", Locale.ENGLISH)
