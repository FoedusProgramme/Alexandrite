package org.unitedsoftware.alexandrite.sdk.agent

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import java.time.Instant

/**
 * A persona a delegating agent may run as a sub-agent, contributed by the config, a plugin or the agent itself.
 *
 * A plugin contributes one with `@Contribute(AgentProfile::class)` on a `@Provides` function that returns it.
 */
@Poko
public class AgentProfile private constructor(
    public val id: AgentId,
    public val name: String,
    /** What a delegating agent reads to choose the profile. */
    public val description: String,
    public val instructions: String,
    /** `<endpoint>/<model>`, null for the delegating agent's. */
    public val model: ModelRef?,
    public val reasoning: ReasoningEffort?,
    public val toolsAllow: List<String>,
    /** Wins over [toolsAllow]. */
    public val toolsDeny: List<String>,
    /** Only ever tightens the chain it runs in. */
    public val mode: PermissionMode?,
    public val maxRounds: Int?,
    public val maxTurnTokens: Long?,
) {
    public fun toBuilder(): Builder = Builder(id, name, description, instructions)
        .model(model)
        .reasoning(reasoning)
        .toolsAllow(toolsAllow)
        .toolsDeny(toolsDeny)
        .mode(mode)
        .maxRounds(maxRounds)
        .maxTurnTokens(maxTurnTokens)

    public class Builder internal constructor(
        private val id: AgentId,
        private var name: String,
        private var description: String,
        private var instructions: String,
    ) {
        private var model: ModelRef? = null
        private var reasoning: ReasoningEffort? = null
        private var toolsAllow: List<String> = listOf("*")
        private var toolsDeny: List<String> = emptyList()
        private var mode: PermissionMode? = null
        private var maxRounds: Int? = null
        private var maxTurnTokens: Long? = null

        public fun name(name: String): Builder = apply { this.name = name }

        public fun description(description: String): Builder = apply { this.description = description }

        public fun instructions(instructions: String): Builder = apply { this.instructions = instructions }

        public fun model(model: ModelRef?): Builder = apply { this.model = model }

        public fun reasoning(reasoning: ReasoningEffort?): Builder = apply { this.reasoning = reasoning }

        public fun toolsAllow(toolsAllow: List<String>): Builder = apply { this.toolsAllow = toolsAllow.toList() }

        public fun toolsDeny(toolsDeny: List<String>): Builder = apply { this.toolsDeny = toolsDeny.toList() }

        public fun mode(mode: PermissionMode?): Builder = apply { this.mode = mode }

        public fun maxRounds(maxRounds: Int?): Builder = apply { this.maxRounds = maxRounds }

        public fun maxTurnTokens(maxTurnTokens: Long?): Builder = apply { this.maxTurnTokens = maxTurnTokens }

        public fun build(): AgentProfile = AgentProfile(
            id,
            name,
            description,
            instructions,
            model,
            reasoning,
            toolsAllow,
            toolsDeny,
            mode,
            maxRounds,
            maxTurnTokens,
        )
    }

    public companion object {
        public fun builder(id: AgentId, name: String, description: String, instructions: String): Builder =
            Builder(id, name, description, instructions)
    }
}

public inline fun AgentProfile.rebuild(block: AgentProfile.Builder.() -> Unit): AgentProfile =
    toBuilder().apply(block).build()

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
