package org.unitedsoftware.alexandrite.agent.permission

import org.unitedsoftware.alexandrite.agent.config.FloorConfig
import org.unitedsoftware.alexandrite.agent.prompt.PromptContributor
import org.unitedsoftware.alexandrite.agent.prompt.PromptOrder
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.PromptSection

internal const val FLOOR_SECTION: String = "alexandrite.floor"

/** The places that the hard floor protects, for an agent offered tools. */
@Singleton
@Contribute(PromptContributor::class)
internal class FloorPrompt(private val floor: AgentFloor) : PromptContributor {
    override val order: Int = PromptOrder.FLOOR

    override fun section(turn: TurnInfo, offered: Set<String>): PromptSection? =
        if (offered.isEmpty()) null else floorSection(floor.entries)
}

/** The places that the hard [floor] protects, null when it protects none. */
internal fun floorSection(floor: FloorConfig): PromptSection? {
    val lines = listOfNotNull(
        floor.deny.takeIf { it.isNotEmpty() }?.let {
            "The operator protects ${places(it)}. Tools refuse these places whatever the request; when one does, " +
                "say so instead of looking for another way in."
        },
        floor.ask.takeIf { it.isNotEmpty() }?.let {
            "A person must allow each call that reaches ${places(it)}, whatever the chat's permission mode."
        },
    )
    if (lines.isEmpty()) return null
    return PromptSection(FLOOR_SECTION, (listOf("# Protected places") + lines).joinToString("\n\n"), stable = true)
}

/** The floor's [entries] as the model reads them. */
private fun places(entries: List<String>): String = entries.joinToString("; ") { entry ->
    when (entry) {
        FloorConfig.CREDENTIALS -> "the credential stores"
        FloorConfig.SYSTEM -> "the system's devices and process entries"
        FloorConfig.ALEXANDRITE -> "Alexandrite's own configuration, data and installation"
        else -> entry
    }
}
