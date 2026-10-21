package org.unitedsoftware.alexandrite.agent.prompt

import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.model.PromptSection

/** A part of the agent that tells the model of itself in the system prompt of the turns that can use it. */
internal interface PromptContributor {
    /** Where its section sits after the agent's own instructions, lower first. */
    val order: Int

    /** The section for [turn], which offers the model the tools named [offered]; null when it has nothing to tell. */
    fun section(turn: TurnInfo, offered: Set<String>): PromptSection?
}

/** The orders of the agent's own contributors. */
internal object PromptOrder {
    const val TOOLS: Int = 10
    const val MANUAL: Int = 20
    const val DELIVERY: Int = 30
    const val WORKSPACE: Int = 40
    const val FLOOR: Int = 50
    const val CONVERSATION_DIRECTORY: Int = 60
    const val PROJECT: Int = 70
    const val SKILLS: Int = 80
}
