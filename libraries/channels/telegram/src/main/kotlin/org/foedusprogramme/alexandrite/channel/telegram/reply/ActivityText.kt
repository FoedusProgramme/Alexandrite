package org.foedusprogramme.alexandrite.channel.telegram.reply

import org.foedusprogramme.alexandrite.channel.telegram.format.FormattedText
import org.foedusprogramme.alexandrite.channel.telegram.format.Span
import org.foedusprogramme.alexandrite.channel.telegram.i18n.TextKeys
import org.foedusprogramme.alexandrite.internal.text.ReviewText
import org.foedusprogramme.alexandrite.sdk.channel.ToolCallStatus
import org.foedusprogramme.alexandrite.sdk.channel.TurnEvent
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import org.foedusprogramme.alexandrite.sdk.tool.ToolActivity

/** One tool call of an activity message, as [TurnEvent]s update it, made by the sub-agent [agent] unless null. */
internal class CallState(val tool: String, val activity: ToolActivity, val title: String, val agent: AgentId? = null) {
    var status: ToolCallStatus = ToolCallStatus.RUNNING
    var content: TurnEvent.Content? = null

    /** Whether the call delegates a task to the sub-agent its title names, and was not refused. */
    val delegates: Boolean get() = tool == DELEGATE_TOOL && status != ToolCallStatus.NOT_RUN
}

/** The statuses that end a call, after which the aggregate line speaks of it in the past tense. */
private val ENDED = setOf(ToolCallStatus.COMPLETED, ToolCallStatus.FAILED)

/** [calls]' activity message: a summary line aggregated by kind, and each call folded below it. */
internal fun renderActivity(texts: Texts, language: LanguageTag?, calls: Collection<CallState>): FormattedText {
    val groups = groupedByKind(calls)
    val summary = summaryLine(texts, language, groups)
    val details = groups.flatten().joinToString("\n") { detailLine(texts, language, it) }
    return FormattedText("$summary\n$details", listOf(Span(BLOCKQUOTE, summary.length + 1, details.length)))
}

/** [calls] grouped by kind in the order each kind first appears, each delegation alone. */
private fun groupedByKind(calls: Collection<CallState>): Collection<List<CallState>> {
    val byKind = LinkedHashMap<Any, MutableList<CallState>>()
    for (call in calls) byKind.getOrPut(if (call.delegates) call else call.activity) { mutableListOf() } += call
    return byKind.values
}

/** One aggregate line per kind of [groups], in order. */
private fun summaryLine(texts: Texts, language: LanguageTag?, groups: Collection<List<CallState>>): String =
    groups.joinToString(" · ") { kindCalls ->
        val first = kindCalls.first()
        if (first.delegates) return@joinToString delegation(texts, language, first)
        val kind = first.activity
        val nounKey = TextKeys.activityNoun(kind.id, kindCalls.size != 1)
        val noun = texts.text(nounKey, language, "count" to kindCalls.size)
        if (kindCalls.all { it.status == ToolCallStatus.NOT_RUN }) {
            texts.text(TextKeys.ACTIVITY_SUMMARY_NOT_RUN, language, "noun" to noun)
        } else {
            val verbKey = TextKeys.activityVerb(kind.id, kindCalls.all { it.status in ENDED })
            "${texts.text(verbKey, language)} $noun"
        }
    }

/** One call, folded under the summary line, after the sub-agent that made it. */
private fun detailLine(texts: Texts, language: LanguageTag?, call: CallState): String {
    val line = if (call.delegates) {
        delegation(texts, language, call) + if (call.status == ToolCallStatus.FAILED) failed(texts, language) else ""
    } else {
        callLine(texts, language, call)
    }
    val agent = call.agent ?: return line
    return texts.text(TextKeys.ACTIVITY_BY_AGENT, language, "agent" to agent, "line" to line)
}

/** That [call] delegates, or delegated, its task to the sub-agent its title names. */
private fun delegation(texts: Texts, language: LanguageTag?, call: CallState): String {
    val key = if (call.status in ENDED) TextKeys.ACTIVITY_DELEGATED else TextKeys.ACTIVITY_DELEGATING
    return texts.text(key, language, "agent" to shortened(ReviewText.escape(call.title)))
}

/** One call, without the sub-agent that made it: the summary already names its verb. */
private fun callLine(texts: Texts, language: LanguageTag?, call: CallState): String {
    val title = shortened(ReviewText.escape(call.title))
    return when (call.status) {
        ToolCallStatus.WAITING_APPROVAL ->
            texts.text(TextKeys.ACTIVITY_WAITING_APPROVAL, language, "tool" to call.tool, "title" to title)

        ToolCallStatus.NOT_RUN -> texts.text(TextKeys.ACTIVITY_NOT_RUN, language, "tool" to call.tool, "title" to title)

        ToolCallStatus.BACKGROUNDED ->
            texts.text(TextKeys.ACTIVITY_BACKGROUNDED, language, "tool" to call.tool, "title" to title)

        else -> title + suffix(texts, language, call)
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
    return if (call.status == ToolCallStatus.FAILED) failed(texts, language) else ""
}

private fun failed(texts: Texts, language: LanguageTag?): String =
    " — " + texts.text(TextKeys.ACTIVITY_FAILED, language)

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

/** The agent's tool that runs a sub-agent. */
private const val DELEGATE_TOOL = "agent.delegate"

/** The characters of a call's title an activity message shows. */
private const val MAX_TITLE_CHARS = 80

private val EXIT_CODE = Regex("""Exit code: (-?\d+)""")
