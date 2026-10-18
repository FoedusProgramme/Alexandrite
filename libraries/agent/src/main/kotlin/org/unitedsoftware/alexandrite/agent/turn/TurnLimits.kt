package org.unitedsoftware.alexandrite.agent.turn

import org.unitedsoftware.alexandrite.agent.i18n.TextKeys
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeKind
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin

/** The source of the entry that asks the model to sum up a turn that reached a limit. */
internal const val LIMIT_SOURCE: String = "alexandrite.limit"

/** What makes a turn's next round its last, which may call no tool. */
internal enum class TurnLimit(
    val notice: NoticeKind,
    /** The key of the notice's text. */
    val key: String,
    /** Why the calls of the last round do not run. */
    val reason: NotRunReason,
    /** What the model reads of such a call. */
    val result: String,
) {
    ROUNDS(
        NoticeKind.ROUND_LIMIT,
        TextKeys.ROUND_LIMIT,
        NotRunReason.ROUND_LIMIT,
        "Not run: the turn used up its tool rounds.",
    ),
    BUDGET(NoticeKind.BUDGET, TextKeys.BUDGET, NotRunReason.BUDGET, "Not run: the turn used up its token budget."),
}

/** The unstored entry that asks the model to sum up the turn that reached [limit] of [maxRounds] or [maxTurnTokens]. */
internal fun limitEntry(limit: TurnLimit, maxRounds: Int?, maxTurnTokens: Long?): UserEntry {
    val reached = when (limit) {
        TurnLimit.ROUNDS -> "its limit of tool rounds ($maxRounds)"
        TurnLimit.BUDGET -> maxTurnTokens?.let { "its token budget ($it tokens)" } ?: "its token budget"
    }
    val text = "[alexandrite:limit]\n" +
        "This turn reached $reached, so this response can call no tool. Say what you have done, what is left and " +
        "how to go on from here.\n" +
        "[/alexandrite:limit]"
    return UserEntry(null, listOf(ContextPart(LIMIT_SOURCE, text)), UserOrigin.SystemWritten(LIMIT_SOURCE))
}
