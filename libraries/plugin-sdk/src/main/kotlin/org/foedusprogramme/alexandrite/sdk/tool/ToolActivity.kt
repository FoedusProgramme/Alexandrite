package org.foedusprogramme.alexandrite.sdk.tool

import kotlinx.serialization.Serializable

/** What kind of effect a tool call has, for a channel to show as it runs. */
@JvmInline
@Serializable
public value class ToolActivity internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        public val READ: ToolActivity = ToolActivity("read")

        public val LIST: ToolActivity = ToolActivity("list")

        public val SEARCH: ToolActivity = ToolActivity("search")

        public val EDIT: ToolActivity = ToolActivity("edit")

        public val DELETE: ToolActivity = ToolActivity("delete")

        public val MOVE: ToolActivity = ToolActivity("move")

        public val EXECUTE: ToolActivity = ToolActivity("execute")

        public val FETCH: ToolActivity = ToolActivity("fetch")

        /** Sending something to a chat. */
        public val SEND: ToolActivity = ToolActivity("send")

        public val THINK: ToolActivity = ToolActivity("think")

        public val OTHER: ToolActivity = ToolActivity("other")

        /** The values this version knows. */
        public val entries: List<ToolActivity> = listOf(
            READ, LIST, SEARCH, EDIT, DELETE, MOVE, EXECUTE, FETCH, SEND, THINK, OTHER,
        )

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): ToolActivity = ToolActivity(id)
    }
}
