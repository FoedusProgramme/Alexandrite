package org.foedusprogramme.alexandrite.channel.telegram.format

import org.foedusprogramme.alexandrite.channel.telegram.api.MessageEntity
import org.foedusprogramme.alexandrite.sdk.channel.Markup

/** A text and its entities, whose offsets and lengths count UTF-16 code units. */
internal data class FormattedText(val text: String, val entities: List<Span> = emptyList()) {
    /** This text cut to [limit] characters at most: its head and `…`, the entities clipped. */
    fun clipped(limit: Int): FormattedText {
        if (text.length <= limit) return this
        var cut = limit - ELLIPSIS.length
        if (cut > 0 && text[cut - 1].isHighSurrogate()) cut--
        val spans = entities.mapNotNull { span ->
            val end = minOf(span.end, cut)
            if (end <= span.offset) null else span.copy(length = end - span.offset)
        }
        return FormattedText(text.substring(0, cut) + ELLIPSIS, spans)
    }

    fun plain(): FormattedText = FormattedText(text)

    /** The entities as Telegram takes them, null for none. */
    fun wireEntities(): List<MessageEntity>? = entities.takeIf { it.isNotEmpty() }?.map {
        MessageEntity(it.type, it.offset, it.length, url = it.url, language = it.language)
    }

    private companion object {
        const val ELLIPSIS = "…"
    }
}

/** A Telegram entity of a [FormattedText]. */
internal data class Span(
    val type: String,
    val offset: Int,
    val length: Int,
    val url: String? = null,
    val language: String? = null,
) {
    val end: Int get() = offset + length
}

internal const val MAX_MESSAGE_LENGTH = 4096

/** [text] in [markup] as Telegram shows it, the text as it is where its Markdown shows nothing. */
internal fun formatted(text: String, markup: Markup): FormattedText {
    if (markup != Markup.MARKDOWN) return FormattedText(text)
    return TelegramMarkdown.render(text).takeIf { it.text.isNotBlank() } ?: FormattedText(text)
}
