package org.unitedsoftware.alexandrite.agent.prompt

import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.directory.ConversationDirectories
import org.unitedsoftware.alexandrite.agent.permission.AgentFloor
import org.unitedsoftware.alexandrite.agent.skill.SKILL_LOAD_TOOL
import org.unitedsoftware.alexandrite.agent.skill.Skills
import org.unitedsoftware.alexandrite.agent.tool.ToolRegistry
import org.unitedsoftware.alexandrite.agent.workspace.Workspaces
import org.unitedsoftware.alexandrite.sdk.agent.AgentProfile
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.PromptSection
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo

/** Puts together the system prompt sections of a turn. */
@Singleton
internal class PromptAssembler(
    private val directory: AgentDirectory,
    private val persona: Persona,
    private val tools: ToolRegistry,
    private val workspaces: Workspaces,
    private val directories: ConversationDirectories,
    private val skills: Skills,
    private val floor: AgentFloor,
    private val info: PluginInfo,
) {

    /** The sections of [turn], a run of [profile] that [delegator] delegated. */
    fun delegated(turn: TurnInfo, profile: AgentProfile, delegator: AgentId): List<PromptSection> = buildList {
        add(baseSection(profile.name, info.version))
        add(delegationSection(delegator))
        val instructions = profile.instructions.takeIf { it.isNotBlank() }
        instructions?.let { add(PromptSection(instructionsSection(1), it, stable = true)) }
        workspace(turn.agent)?.let(::add)
        addConversationDirectory(turn)
        addSkills(turn)
        turn.language?.let { add(languageSection(it)) }
    }

    /** The sections of [turn] in a chat of [chatKind], null when the turn comes from no message. */
    suspend fun sections(turn: TurnInfo, chatKind: ChatKind?): List<PromptSection> {
        val agent = requireNotNull(directory.agent(turn.agent)) { "No agent '${turn.agent}' is configured." }
        return buildList {
            add(baseSection(agent.name, info.version))
            addAll(persona.sections(agent.id, PersonaConditions(chatKind, turn.kind)))
            workspace(agent.id)?.let(::add)
            addConversationDirectory(turn)
            addSkills(turn)
            turn.language?.let { add(languageSection(it)) }
        }
    }

    /** The workspace section of [agent], for an agent that selects a tool. */
    private fun workspace(agent: AgentId): PromptSection? = workspaces.paths[agent]?.takeIf {
        tools.offered(agent).isNotEmpty()
    }?.let { workspaceSection(it, floor.entries) }

    /** The skills section of [turn], for an agent offered `skill.load`. */
    private fun MutableList<PromptSection>.addSkills(turn: TurnInfo) {
        if (tools.offered(turn.agent).any { it.definition.name == SKILL_LOAD_TOOL }) {
            add(skillsSection(skills.global(turn.agent).values))
        }
    }

    /** The conversation directory section of [turn], for an agent that selects at least one tool. */
    private fun MutableList<PromptSection>.addConversationDirectory(turn: TurnInfo) {
        if (tools.offered(turn.agent).isNotEmpty()) add(conversationDirectorySection(directories.path(turn)))
    }
}
