package org.foedusprogramme.alexandrite.agent.prompt

import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.model.PromptSection

/** Puts together the system prompt sections of a turn. */
@Singleton
internal class PromptAssembler(private val directory: AgentDirectory, private val persona: Persona) {
    /** The sections of [turn] in a chat of [chatKind], null when the turn comes from no message. */
    suspend fun sections(turn: TurnInfo, chatKind: ChatKind?): List<PromptSection> {
        val agent = requireNotNull(directory.agent(turn.agent)) { "No agent '${turn.agent}' is configured." }
        return buildList {
            add(baseSection(agent.name))
            addAll(persona.sections(agent.id, PersonaConditions(chatKind, turn.kind)))
            turn.language?.let { add(languageSection(it)) }
        }
    }
}
