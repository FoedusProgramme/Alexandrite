package org.foedusprogramme.alexandrite.sdk.transcript

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi

/** How a tool call ended. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ToolOutcome {
    /** Whether the model is told that the call failed. */
    public val isError: Boolean

    /** The tool ran and succeeded. */
    @OptIn(InternalAlexandriteApi::class)
    public data object Succeeded : ToolOutcome {
        override val isError: Boolean get() = false
    }

    /** The tool ran and failed. */
    @OptIn(InternalAlexandriteApi::class)
    public data object Failed : ToolOutcome {
        override val isError: Boolean get() = true
    }

    /** The call was cancelled while it ran, so what it did is unknown. */
    @OptIn(InternalAlexandriteApi::class)
    public data object Cancelled : ToolOutcome {
        override val isError: Boolean get() = true
    }

    /** The tool did not run. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class NotRun(
        public val reason: NotRunReason,
        /** The id of the approval the call is waiting for or was refused by, null when there is none. */
        public val approval: String? = null,
    ) : ToolOutcome {
        override val isError: Boolean get() = true
    }

    /** An outcome of a type this version does not know, as stored. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Unknown(
        public val type: String,
        /** The stored object, its type included. */
        public val json: JsonObject,
    ) : ToolOutcome {
        override val isError: Boolean get() = true
    }
}

/** Why a tool did not run. */
@JvmInline
@Serializable
public value class NotRunReason internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** The arguments are no JSON object or do not fit the tool's parameters. */
        public val INVALID_ARGUMENTS: NotRunReason = NotRunReason("invalid_arguments")

        /** No tool of the turn has the name the model called. */
        public val UNKNOWN_TOOL: NotRunReason = NotRunReason("unknown_tool")

        /** A `tool.before` hook refused the call. */
        public val HOOK_DENIED: NotRunReason = NotRunReason("hook_denied")

        /** The turn ran out of rounds before the call could run. */
        public val ROUND_LIMIT: NotRunReason = NotRunReason("round_limit")

        /** The turn offers the tool but may not call it. */
        public val NOT_PERMITTED: NotRunReason = NotRunReason("not_permitted")

        /** The hard floor or the tool's review refused the call. */
        public val FLOOR: NotRunReason = NotRunReason("floor")

        /** The chat is in plan mode. */
        public val PLAN_MODE: NotRunReason = NotRunReason("plan_mode")

        /** An approver denied the call. */
        public val DENIED: NotRunReason = NotRunReason("denied")

        /** A rule, the side model or the lack of an approver refused the call. */
        public val DENIED_BY_POLICY: NotRunReason = NotRunReason("denied_by_policy")

        /** No approver answered in time. */
        public val EXPIRED: NotRunReason = NotRunReason("expired")

        /** The user wrote a message instead of answering. */
        public val DECLINED: NotRunReason = NotRunReason("declined")

        /** The turn was cancelled while the call waited for an approver. */
        public val CANCELLED: NotRunReason = NotRunReason("cancelled")

        /** The agent stopped while the call waited for an approver. */
        public val INTERRUPTED: NotRunReason = NotRunReason("interrupted")

        /** The values this version knows. */
        public val entries: List<NotRunReason> = listOf(
            INVALID_ARGUMENTS,
            UNKNOWN_TOOL,
            HOOK_DENIED,
            ROUND_LIMIT,
            NOT_PERMITTED,
            FLOOR,
            PLAN_MODE,
            DENIED,
            DENIED_BY_POLICY,
            EXPIRED,
            DECLINED,
            CANCELLED,
            INTERRUPTED,
        )

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): NotRunReason = NotRunReason(id)
    }
}
