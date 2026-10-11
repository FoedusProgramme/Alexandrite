package org.foedusprogramme.alexandrite.agent.permission

import org.foedusprogramme.alexandrite.agent.prompt.Escaper

/** What the model reads of the calls that the permission layer refuses. */
internal object Refusals {
    fun planMode(tool: String): String =
        "[alexandrite:plan_mode] Not run: this chat is in plan mode. Describe what you would do with \"$tool\" (the " +
            "exact call and why) instead of doing it. Do not retry."

    /** [reason] starts in lower case and ends without a full stop. */
    fun deniedByPolicy(reason: String): String =
        "[alexandrite:denied by=policy] Not run: ${Escaper.HEADERS.text(reason)}. Do not retry this or an " +
            "equivalent call; tell the user."

    fun blockedFloor(reason: String): String = "[alexandrite:blocked_floor] ${Escaper.HEADERS.text(reason)}"
}
