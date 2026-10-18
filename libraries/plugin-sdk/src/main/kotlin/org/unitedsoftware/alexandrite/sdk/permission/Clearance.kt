package org.unitedsoftware.alexandrite.sdk.permission

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser

/** Who or what let a tool call run. */
@Poko
public class Clearance(
    public val kind: ClearanceKind,
    /** The person who allowed the call, null when nobody did. */
    public val by: ChatUser? = null,
    /** The snapshot of the call's review, which the tool compares before it acts. */
    public val snapshot: String? = null,
)

/** What let a tool call run. */
@JvmInline
@Serializable
public value class ClearanceKind internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** The call is not gated. */
        public val NONE: ClearanceKind = ClearanceKind("none")

        /** The chat's permission mode. */
        public val MODE: ClearanceKind = ClearanceKind("mode")

        /** An allow rule. */
        public val RULE: ClearanceKind = ClearanceKind("rule")

        /** The side model that judges calls. */
        public val JUDGE: ClearanceKind = ClearanceKind("judge")

        /** An approver. */
        public val HUMAN: ClearanceKind = ClearanceKind("human")

        /** A write in the turn's own conversation directory. */
        public val SCRATCH: ClearanceKind = ClearanceKind("scratch")

        /** The values this version knows. */
        public val entries: List<ClearanceKind> = listOf(NONE, MODE, RULE, JUDGE, HUMAN, SCRATCH)

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): ClearanceKind = ClearanceKind(id)
    }
}
