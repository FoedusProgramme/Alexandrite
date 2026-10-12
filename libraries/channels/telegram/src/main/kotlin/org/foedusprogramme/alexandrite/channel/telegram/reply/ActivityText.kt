package org.foedusprogramme.alexandrite.channel.telegram.reply

import org.foedusprogramme.alexandrite.channel.telegram.format.FormattedText
import org.foedusprogramme.alexandrite.channel.telegram.format.Span
import org.foedusprogramme.alexandrite.channel.telegram.i18n.TextKeys
import org.foedusprogramme.alexandrite.internal.text.ReviewText
import org.foedusprogramme.alexandrite.sdk.channel.ToolCallStatus
import org.foedusprogramme.alexandrite.sdk.channel.TurnEvent
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import org.foedusprogramme.alexandrite.sdk.tool.ToolActivity

/** One tool call of an activity message, as [TurnEvent]s update it. */
internal class CallState(val tool: String, val activity: ToolActivity, val title: String) {
    var status: ToolCallStatus = ToolCallStatus.RUNNING
    var content: TurnEvent.Content? = null
}

/** The statuses that end a call, after which the aggregate line speaks of it in the past tense. */
private val ENDED = setOf(ToolCallStatus.COMPLETED, ToolCallStatus.FAILED)

/** [calls]' activity message: a summary line aggregated by kind, and each call folded below it. */
internal fun renderActivity(texts: Texts, language: LanguageTag?, calls: Collection<CallState>): FormattedText {
    val summary = summaryLine(texts, language, calls)
    val details = calls.joinToString("\n") { detailLine(texts, language, it) }
    return FormattedText("$summary\n$details", listOf(Span(BLOCKQUOTE, summary.length + 1, details.length)))
}

/** The calls grouped by kind, in the order each kind first appears, aggregated into one line. */
private fun summaryLine(texts: Texts, language: LanguageTag?, calls: Collection<CallState>): String {
    val byKind = LinkedHashMap<ToolActivity, MutableList<CallState>>()
    for (call in calls) byKind.getOrPut(call.activity) { mutableListOf() } += call
    return byKind.entries.joinToString(" · ") { (kind, kindCalls) ->
        val nounKey = TextKeys.activityNoun(kind.id, kindCalls.size != 1)
        val noun = texts.text(nounKey, language, "count" to kindCalls.size)
        if (kindCalls.all { it.status == ToolCallStatus.NOT_RUN }) {
            texts.text(TextKeys.ACTIVITY_SUMMARY_NOT_RUN, language, "noun" to noun)
        } else {
            val verbKey = TextKeys.activityVerb(kind.id, kindCalls.all { it.status in ENDED })
            "${texts.text(verbKey, language)} $noun"
        }
    }
}

/** One call, folded under the summary line. */
private fun detailLine(texts: Texts, language: LanguageTag?, call: CallState): String {
    val title = shortened(ReviewText.escape(call.title))
    return when (call.status) {
        ToolCallStatus.WAITING_APPROVAL ->
            texts.text(TextKeys.ACTIVITY_WAITING_APPROVAL, language, "tool" to call.tool, "title" to title)

        ToolCallStatus.NOT_RUN -> texts.text(TextKeys.ACTIVITY_NOT_RUN, language, "tool" to call.tool, "title" to title)

        ToolCallStatus.BACKGROUNDED ->
            texts.text(TextKeys.ACTIVITY_BACKGROUNDED, language, "tool" to call.tool, "title" to title)

        else -> {
            val past = call.status != ToolCallStatus.RUNNING
            if (call.activity == ToolActivity.SEARCH) {
                val key = if (past) TextKeys.ACTIVITY_SEARCHED else TextKeys.ACTIVITY_SEARCHING
                return texts.text(key, language, "title" to title) + suffix(texts, language, call)
            }
            val verb = texts.text(TextKeys.activityVerb(call.activity.id, past), language)
            "$verb $title${suffix(texts, language, call)}"
        }
    }
}

/** What a finished call's line adds after its title: an exit code, a diff's line counts, or that it failed. */
private fun suffix(texts: Texts, language: LanguageTag?, call: CallState): String {
    when (val content = call.content) {
        is TurnEvent.Content.Output -> exitCode(content.tail)?.let {
            return " — " + texts.text(TextKeys.ACTIVITY_EXIT, language, "code" to it)
        }

        is TurnEvent.Content.Diff -> lineCounts(content.unified)?.let { (added, removed) ->
            return " (" + texts.text(TextKeys.ACTIVITY_DIFF, language, "added" to added, "removed" to removed) + ")"
        }

        else -> Unit
    }
    return if (call.status == ToolCallStatus.FAILED) " — " + texts.text(TextKeys.ACTIVITY_FAILED, language) else ""
}

/** The exit code [tail] names, null when it names none. */
private fun exitCode(tail: String): Int? = EXIT_CODE.find(tail)?.groupValues?.get(1)?.toIntOrNull()

/** The added and removed line counts of the unified diff [text], null when it changes nothing. */
private fun lineCounts(text: String): Pair<Int, Int>? {
    var added = 0
    var removed = 0
    for (line in text.lineSequence()) {
        when {
            line.startsWith("+++") || line.startsWith("---") -> Unit
            line.startsWith("+") -> added++
            line.startsWith("-") -> removed++
        }
    }
    return (added to removed).takeIf { added > 0 || removed > 0 }
}

/** [text], cut to [MAX_TITLE_CHARS] characters with an ellipsis. */
private fun shortened(text: String): String {
    if (text.length <= MAX_TITLE_CHARS) return text
    val end = if (text[MAX_TITLE_CHARS - 1].isHighSurrogate()) MAX_TITLE_CHARS - 1 else MAX_TITLE_CHARS
    return text.substring(0, end) + "…"
}

private const val BLOCKQUOTE = "expandable_blockquote"

/** The characters of a call's title an activity message shows. */
private const val MAX_TITLE_CHARS = 80

private val EXIT_CODE = Regex("""Exit code: (-?\d+)""")
