package org.foedusprogramme.alexandrite.agent.prompt

import org.foedusprogramme.alexandrite.sdk.channel.IncomingMessage
import org.foedusprogramme.alexandrite.sdk.transcript.ContextPart
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserOrigin
import org.foedusprogramme.alexandrite.sdk.transcript.UserPart
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The source of the framing parts of format v1. */
internal const val MESSAGE_SOURCE: String = "alexandrite.message"

/** The unstored user entry of [message] with [text] as its words. */
internal fun messageEntry(message: IncomingMessage, text: String, zone: ZoneId, linked: Boolean): UserEntry {
    val parts = buildList<UserPart> {
        add(ContextPart(MESSAGE_SOURCE, messageFraming(message, zone, linked)))
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
    append("Chat: ").append(message.chatInfo.kind)
    message.chatInfo.title?.let { append(" \"").append(oneLine(it)).append('"') }
    if (linked) append(" on ").append(message.chat.instance)
    appendLine()
    message.forwarded?.let { appendLine("Forwarded from: ${oneLine(it.name ?: UNKNOWN)} (${it.kind})") }
    message.quote?.let { quote ->
        val trust = if (quote.trust.live) "verified" else "unverified"
        appendLine("Quoting: ${oneLine(quote.senderName ?: UNKNOWN)} ($trust)")
        lines(quote.text).forEach { appendLine(if (it.isEmpty()) ">" else "> $it") }
    }
    message.facts.forEach { appendLine("${oneLine(it.label)}: ${oneLine(it.value)}") }
    append("[/alexandrite:message]")
}

private const val UNKNOWN = "unknown"

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mmXXX", Locale.ROOT)
