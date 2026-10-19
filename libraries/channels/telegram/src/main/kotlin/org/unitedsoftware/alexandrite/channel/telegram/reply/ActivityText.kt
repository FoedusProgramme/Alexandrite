package org.unitedsoftware.alexandrite.channel.telegram.reply

import org.unitedsoftware.alexandrite.channel.telegram.format.FormattedText
import org.unitedsoftware.alexandrite.channel.telegram.format.MAX_MESSAGE_LENGTH
import org.unitedsoftware.alexandrite.channel.telegram.format.Span
import org.unitedsoftware.alexandrite.channel.telegram.i18n.TextKeys
import org.unitedsoftware.alexandrite.internal.text.ReviewText
import org.unitedsoftware.alexandrite.internal.text.cutWithEllipsis
import org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus
import org.unitedsoftware.alexandrite.sdk.channel.TurnEvent
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity

/** One tool call of an activity message, as [TurnEvent]s update it, made by the sub-agent [agent] unless null. */
internal class CallState(val tool: String, val activity: ToolActivity, var title: String, val agent: AgentId? = null) {
    var status: ToolCallStatus = ToolCallStatus.RUNNING
    var content: TurnEvent.Content? = null

    /** The background run that the call started, once its activity was handed over. */
    var run: RunId? = null

    /** Whether the call delegates a task to the sub-agent its title names, and was not refused. */
    val delegates: Boolean get() = activity == ToolActivity.DELEGATE && status != ToolCallStatus.NOT_RUN
}

/** The statuses that end a call, after which a delegation's own line speaks of it in the past tense. */
private val ENDED = setOf(ToolCallStatus.COMPLETED, ToolCallStatus.FAILED, ToolCallStatus.BACKGROUNDED)

/** The statuses that count as still running, for a kind's aggregate verb. */
private val ACTIVE = setOf(ToolCallStatus.RUNNING, ToolCallStatus.WAITING_APPROVAL)

/** [calls]' activity message: a summary line aggregated by kind, and each call folded below it. */
internal fun renderActivity(texts: Texts, language: LanguageTag?, calls: Collection<CallState>): FormattedText {
    val groups = groupedByKind(calls)
    val summary = summaryLine(texts, language, groups)
    val lines = groups.flatten().mapNotNull { detailLine(texts, language, it) }
    return assembled(texts, language, summary, lines)
}

/** [summary] with [lines] below it in one message, its oldest shown lines dropped and counted when they do not fit. */
private fun assembled(texts: Texts, language: LanguageTag?, summary: String, lines: List<String>): FormattedText {
    if (lines.isEmpty()) return FormattedText(summary)
    val room = MAX_MESSAGE_LENGTH - summary.length - 1
    var used = 0
    var kept = 0
    for (line in lines.asReversed()) {
        val next = used + line.length + if (kept > 0) 1 else 0
        val omitted = lines.size - kept - 1
        val notice = if (omitted > 0) omittedNotice(texts, language, omitted).length + 1 else 0
        if (next + notice > room) break
        used = next
        kept++
    }
    val omitted = lines.size - kept
    val shown = lines.takeLast(kept)
    val details = (if (omitted > 0) listOf(omittedNotice(texts, language, omitted)) + shown else shown)
        .joinToString("\n")
    return FormattedText("$summary\n$details", listOf(Span(BLOCKQUOTE, summary.length + 1, details.length)))
}

private fun omittedNotice(texts: Texts, language: LanguageTag?, omitted: Int): String =
    texts.text(TextKeys.activityOmitted(omitted != 1), language, "count" to omitted)

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
        val ran = kindCalls.filterNot { it.status == ToolCallStatus.NOT_RUN }
        if (ran.isEmpty()) {
            val noun = nounPhrase(texts, language, kind.id, kindCalls.size)
            texts.text(TextKeys.ACTIVITY_SUMMARY_NOT_RUN, language, "noun" to noun)
        } else {
            val verbKey = TextKeys.activityVerb(kind.id, ran.none { it.status in ACTIVE })
            "${texts.text(verbKey, language)} ${nounPhrase(texts, language, kind.id, ran.size)}"
        }
    }

/** The noun phrase of one activity kind's [count] calls. */
private fun nounPhrase(texts: Texts, language: LanguageTag?, kind: String, count: Int): String =
    texts.text(TextKeys.activityNoun(kind, count != 1), language, "count" to count)

/** One call, folded under the summary line, after the sub-agent that made it; null when the summary already said it. */
private fun detailLine(texts: Texts, language: LanguageTag?, call: CallState): String? {
    val line = if (call.delegates) {
        if (call.status != ToolCallStatus.FAILED) return null
        delegation(texts, language, call) + failed(texts, language)
    } else {
        callLine(texts, language, call)
    }
    val agent = call.agent ?: return line
    return texts.text(TextKeys.ACTIVITY_BY_AGENT, language, "agent" to agent, "line" to line)
}

/** That [call] delegates, or delegated, its task to the sub-agent its title names. */
private fun delegation(texts: Texts, language: LanguageTag?, call: CallState): String {
    val key = if (call.status in ENDED) TextKeys.ACTIVITY_DELEGATED else TextKeys.ACTIVITY_DELEGATING
    val agent = shortened(ReviewText.escape(call.title))
    val named = call.run?.let { texts.text(TextKeys.ACTIVITY_RUN, language, "agent" to agent, "run" to it) } ?: agent
    return texts.text(key, language, "agent" to named)
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

private fun shortened(text: String): String = cutWithEllipsis(text, MAX_TITLE_CHARS)

private const val BLOCKQUOTE = "expandable_blockquote"

/** The characters of a call's title an activity message shows. */
private const val MAX_TITLE_CHARS = 80

private val EXIT_CODE = Regex("""Exit code: (-?\d+)""")
