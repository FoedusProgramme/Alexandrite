package org.unitedsoftware.alexandrite.agent.delegation

import org.unitedsoftware.alexandrite.agent.prompt.Escaper
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.sdk.transcript.UserPart

/** The source of the entry that hands a sub-agent run its task. */
internal const val DELEGATION_SOURCE: String = "alexandrite.delegation"

/** The unstored entry that hands [delegation] its task, which reads [text], with [carried] between them. */
internal fun delegationEntry(delegation: Delegation, text: String, carried: List<UserPart> = emptyList()): UserEntry {
    val parts = buildList<UserPart> {
        add(ContextPart(DELEGATION_SOURCE, delegationFraming(delegation)))
        addAll(carried)
        if (text.isNotEmpty()) add(TextPart(Escaper.HEADERS.text(text)))
    }
    return UserEntry(null, parts, UserOrigin.SystemWritten(DELEGATION_SOURCE))
}

/** Delegation framing v1: who delegated the run, along which chain, and the run's id. */
internal fun delegationFraming(delegation: Delegation): String = buildString {
    appendLine("[alexandrite:delegated]")
    appendLine("Delegated by: ${delegation.delegator}")
    appendLine("Chain: ${delegation.chain}")
    appendLine("Run: ${delegation.run}")
    append("[/alexandrite:delegated]")
}

/** Run framing v1, after a run's result: the id that `agent.delegate` takes to resume the run. */
internal fun runFraming(run: RunId): String = "\n[alexandrite:run $run]"

/** The agent that delegated the run. */
internal val Delegation.delegator: AgentId get() = chain.agents[chain.agents.size - 2]

/** What the model reads of a delegation that would grow the tree past [MAX_DEPTH]. */
internal const val DELEGATION_LIMIT_RESULT: String =
    "[alexandrite:delegation_limit] Not run: sub-agents may delegate only $MAX_DEPTH levels deep, and this run is " +
        "at the last level. Do the task yourself."

/** What the model reads of a background delegation once its agent used up a budget. */
internal const val BUDGET_SPENT_RESULT: String =
    "[alexandrite:budget] Not run: the agent has used up its budget, so it starts no background runs until the " +
        "budget renews. Do the task yourself, or tell the user."

/** What the model reads of the delegate call of a run that a restart cut off. */
internal const val RUN_INTERRUPTED_RESULT: String =
    "[alexandrite:interrupted] Not run to its end: Alexandrite stopped while the sub-agent worked on this task, and " +
        "the run was not started again. What it did is unknown."
