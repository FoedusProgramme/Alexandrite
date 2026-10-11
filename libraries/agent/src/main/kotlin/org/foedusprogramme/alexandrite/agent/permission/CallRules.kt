package org.foedusprogramme.alexandrite.agent.permission

import org.foedusprogramme.alexandrite.sdk.di.Binds
import org.foedusprogramme.alexandrite.sdk.di.Singleton

/** The deny and allow rules of tool calls. */
internal interface CallRules {
    /** Why a deny rule refuses [call], null when none does. */
    suspend fun denied(call: CheckedCall): String?

    /** Whether an allow rule lets [call] run. */
    suspend fun allowed(call: CheckedCall): Boolean
}

/** Placeholder until the allow and deny rules (T3.7). */
@Singleton
@Binds(CallRules::class)
internal class NoCallRules : CallRules {
    override suspend fun denied(call: CheckedCall): String? = null

    override suspend fun allowed(call: CheckedCall): Boolean = false
}
