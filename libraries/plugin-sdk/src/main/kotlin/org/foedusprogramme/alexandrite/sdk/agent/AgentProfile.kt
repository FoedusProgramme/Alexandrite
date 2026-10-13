package org.foedusprogramme.alexandrite.sdk.agent

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.permission.PermissionMode
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import java.time.Instant

/**
 * A persona a delegating agent may run as a sub-agent, contributed by the config, a plugin or the agent itself.
 *
 * A plugin contributes one with `@Contribute(AgentProfile::class)` on a `@Provides` function.
 */
@Poko
public class AgentProfile(
    public val id: AgentId,
    public val name: String,
    /** What a delegating agent reads to choose the profile. */
    public val description: String,
    public val instructions: String,
    /** `<endpoint>/<model>`, null for the delegating agent's. */
    public val model: ModelRef? = null,
    public val reasoning: ReasoningEffort? = null,
    public val toolsAllow: List<String> = listOf("*"),
    /** Wins over [toolsAllow]. */
    public val toolsDeny: List<String> = emptyList(),
    /** Only ever tightens the chain it runs in. */
    public val mode: PermissionMode? = null,
    public val maxRounds: Int? = null,
    public val maxTurnTokens: Long? = null,
)

/** Where an [AgentProfile] comes from. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ProfileSource {
    /** Declared at `agent.agents.<id>` without `channels`. */
    @OptIn(InternalAlexandriteApi::class)
    public data object Config : ProfileSource

    /** Contributed by a plugin. */
    @OptIn(InternalAlexandriteApi::class)
    public data object Plugin : ProfileSource

    /** Created by the agent itself, through `profile.create`. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Created(
        /** Null for the host. */
        public val by: ChatUser?,
        public val at: Instant,
    ) : ProfileSource
}
