package org.unitedsoftware.alexandrite.agent.prompt

import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.sdk.agent.AgentProfile
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.PromptSection
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo

/**
 * Puts together the system prompt sections of a turn: the base, the agent's own instructions, what the agent's parts
 * that the turn can use tell of themselves, and the chat's language.
 */
@Singleton
internal class PromptAssembler(
    private val directory: AgentDirectory,
    private val persona: Persona,
    contributors: List<PromptContributor>,
    private val info: PluginInfo,
) {
    private val contributors = contributors.sortedBy { it.order }

    /** The sections of [turn], a run of [profile] that [delegator] delegated, which offers the tools [offered]. */
    fun delegated(
        turn: TurnInfo,
        offered: Set<String>,
        profile: AgentProfile,
        delegator: AgentId,
    ): List<PromptSection> = buildList {
        add(baseSection(profile.name, info.version))
        add(delegationSection(delegator))
        profile.instructions.takeIf {
            it.isNotBlank()
        }?.let { add(PromptSection(instructionsSection(1), it, true)) }
        addContributed(turn, offered)
        turn.language?.let { add(languageSection(it)) }
    }

    /** The sections of [turn] in a chat of [chatKind], null when it comes from no message, which offers [offered]. */
    suspend fun sections(turn: TurnInfo, offered: Set<String>, chatKind: ChatKind?): List<PromptSection> {
        val agent = requireNotNull(directory.agent(turn.agent)) { "No agent '${turn.agent}' is configured." }
        return buildList {
            add(baseSection(agent.name, info.version))
            addAll(persona.sections(agent.id, PersonaConditions(chatKind, turn.kind)))
            addContributed(turn, offered)
            turn.language?.let { add(languageSection(it)) }
        }
    }

    private fun MutableList<PromptSection>.addContributed(turn: TurnInfo, offered: Set<String>) {
        contributors.mapNotNullTo(this) { it.section(turn, offered) }
    }
}
