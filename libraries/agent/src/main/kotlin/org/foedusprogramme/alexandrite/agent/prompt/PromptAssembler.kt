package org.foedusprogramme.alexandrite.agent.prompt

import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
import org.foedusprogramme.alexandrite.agent.tool.ToolRegistry
import org.foedusprogramme.alexandrite.agent.workspace.Workspaces
import org.foedusprogramme.alexandrite.sdk.agent.AgentProfile
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.model.PromptSection

/** Puts together the system prompt sections of a turn. */
@Singleton
internal class PromptAssembler(
    private val directory: AgentDirectory,
    private val persona: Persona,
    tools: ToolRegistry,
    workspaces: Workspaces,
) {
    /** The workspace section of each agent that selects a tool. */
    private val workspaceSections = workspaces.paths.filterKeys { tools.offered(it).isNotEmpty() }
        .mapValues { (_, workspace) -> workspaceSection(workspace) }

    /** The sections of [turn], a run of [profile] that [delegator] delegated. */
    fun delegated(turn: TurnInfo, profile: AgentProfile, delegator: AgentId): List<PromptSection> = buildList {
        add(baseSection(profile.name))
        add(delegationSection(delegator))
        val instructions = profile.instructions.takeIf { it.isNotBlank() }
        instructions?.let { add(PromptSection(instructionsSection(1), it, stable = true)) }
        workspaceSections[turn.agent]?.let(::add)
        turn.language?.let { add(languageSection(it)) }
    }

    /** The sections of [turn] in a chat of [chatKind], null when the turn comes from no message. */
    suspend fun sections(turn: TurnInfo, chatKind: ChatKind?): List<PromptSection> {
        val agent = requireNotNull(directory.agent(turn.agent)) { "No agent '${turn.agent}' is configured." }
        return buildList {
            add(baseSection(agent.name))
            addAll(persona.sections(agent.id, PersonaConditions(chatKind, turn.kind)))
            workspaceSections[agent.id]?.let(::add)
            turn.language?.let { add(languageSection(it)) }
        }
    }
}
