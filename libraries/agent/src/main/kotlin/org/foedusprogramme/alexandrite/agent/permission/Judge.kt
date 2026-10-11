package org.foedusprogramme.alexandrite.agent.permission

import org.foedusprogramme.alexandrite.sdk.di.Binds
import org.foedusprogramme.alexandrite.sdk.di.Singleton

/** Decides the gated calls of an operator's turn in `auto` mode in a person's place. */
internal interface Judge {
    suspend fun judge(call: CheckedCall): Verdict
}

/** What a [Judge] decided. */
internal sealed interface Verdict {
    data object Allow : Verdict

    class Deny(val reason: String) : Verdict

    /** A person decides, told [reason]. */
    class Ask(val reason: String) : Verdict
}

/** Placeholder until the side-model judge (T3.6). */
@Singleton
@Binds(Judge::class)
internal class AskingJudge : Judge {
    override suspend fun judge(call: CheckedCall): Verdict = Verdict.Ask("no judge decides calls yet")
}
