package org.unitedsoftware.alexandrite.agent.permission

import org.unitedsoftware.alexandrite.agent.prompt.Escaper

/** What the model reads of the calls that the permission layer refuses. */
internal object Refusals {
    const val DENIED_BY_ADMIN: String = "[alexandrite:denied by=admin] Not run: an administrator denied this call. " +
        "Do not retry it or an equivalent call; tell the user and go on without it."

    const val EXPIRED: String = "[alexandrite:expired] Not run: nobody answered the approval in time. Do not retry " +
        "it now; if the user still wants it, they will say so."

    const val DECLINED: String = "[alexandrite:declined] Not run: instead of answering the approval, the user sent " +
        "the message that follows. Do what it says."

    const val DECLINED_IN_RUN: String = "[alexandrite:declined] Not run: instead of answering the approval, the user " +
        "wrote to the agent that delegated this task. End your run with a reply that says what you have done; that " +
        "agent reads their message."

    const val DENIED_IN_TREE: String = "[alexandrite:denied by=admin] Not run: an administrator denied this same " +
        "call earlier in this task. Do not retry it or an equivalent call; say so and go on without it."

    const val CANCELLED: String = "[alexandrite:cancelled] Not run: the turn was cancelled before this call ran."

    const val INTERRUPTED: String =
        "[alexandrite:interrupted] Not run: Alexandrite stopped while this call waited for approval."

    fun planMode(tool: String): String =
        "[alexandrite:plan_mode] Not run: this chat is in plan mode. Describe what you would do with \"$tool\" (the " +
            "exact call and why) instead of doing it. Do not retry."

    /** [reason] starts in lower case and ends without a full stop. */
    fun deniedByPolicy(reason: String): String =
        "[alexandrite:denied by=policy] Not run: ${Escaper.HEADERS.text(reason)}. Do not retry this or an " +
            "equivalent call; tell the user."

    fun blockedFloor(reason: String): String = "[alexandrite:blocked_floor] ${Escaper.HEADERS.text(reason)}"
}
