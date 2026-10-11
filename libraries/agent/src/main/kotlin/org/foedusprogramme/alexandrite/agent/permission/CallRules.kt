package org.foedusprogramme.alexandrite.agent.permission

import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.di.Binds
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.tool.RuleSubject

/** The deny and allow rules of tool calls. */
internal interface CallRules {
    /** Why a deny rule refuses [call], null when none does. */
    suspend fun denied(call: CheckedCall): String?

    /** Whether an allow rule lets [call] run. */
    suspend fun allowed(call: CheckedCall): Boolean

    /** What an approver of [call] may allow for good, null when nothing. */
    suspend fun grantable(call: CheckedCall): RuleSubject?

    /** Lets the calls that [subject] covers run in the session of [call], as [by] allowed. */
    suspend fun grant(call: CheckedCall, subject: RuleSubject, by: ChatUser)
}

/** Placeholder until the allow and deny rules (T3.7). */
@Singleton
@Binds(CallRules::class)
internal class NoCallRules : CallRules {
    override suspend fun denied(call: CheckedCall): String? = null

    override suspend fun allowed(call: CheckedCall): Boolean = false

    override suspend fun grantable(call: CheckedCall): RuleSubject? = null

    override suspend fun grant(call: CheckedCall, subject: RuleSubject, by: ChatUser) = Unit
}
