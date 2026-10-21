package org.unitedsoftware.alexandrite.sdk.permission

import kotlinx.serialization.Serializable

/** How an agent decides the gated tool calls of an operator's turn in one chat. */
@JvmInline
@Serializable
public value class PermissionMode internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** No gated call runs: the model describes what it would do. */
        public val PLAN: PermissionMode = PermissionMode("plan")

        /** A human is asked about every gated call. */
        public val MANUAL: PermissionMode = PermissionMode("manual")

        /** Allow rules first, then a human. */
        public val ASK: PermissionMode = PermissionMode("ask")

        /**
         * File writes run where the turn's writes may land, as a sandbox lets commands write: the agent's workspace, the
         * conversation's directory, the folders it trusts, the temp directory and the sandbox's writable paths; the rest
         * as in [ASK].
         */
        public val ALLOW_EDIT: PermissionMode = PermissionMode("allowedit")

        /** Allow rules, then a side model, then a human. */
        public val AUTO: PermissionMode = PermissionMode("auto")

        /** Every gated call runs, for a limited time. */
        public val BYPASS: PermissionMode = PermissionMode("bypass")

        /** The values this version knows. */
        public val entries: List<PermissionMode> = listOf(PLAN, MANUAL, ASK, ALLOW_EDIT, AUTO, BYPASS)

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): PermissionMode = PermissionMode(id)
    }
}
