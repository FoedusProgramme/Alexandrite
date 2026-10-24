package org.unitedsoftware.alexandrite.agent.manual

import org.unitedsoftware.alexandrite.agent.prompt.PromptContributor
import org.unitedsoftware.alexandrite.agent.prompt.PromptOrder
import org.unitedsoftware.alexandrite.agent.prompt.PromptTurn
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.PromptSection

internal const val MANUAL_SECTION: String = "alexandrite.manual"

/** Where a turn that offers the manual looks up how Alexandrite works. */
@Singleton
@Contribute(PromptContributor::class)
internal class ManualPrompt : PromptContributor {
    override val order: Int = PromptOrder.MANUAL

    override fun section(turn: PromptTurn): PromptSection? = MANUAL.takeIf { MANUAL_TOOL in turn.offered }
}

internal val MANUAL: PromptSection = PromptSection(
    MANUAL_SECTION,
    "# Manual\n\nFor questions about Alexandrite itself, such as its commands, settings, permissions, taint and " +
        "trust, MCP servers, persona and skills, read its manual with $MANUAL_TOOL before answering.",
    stable = true,
)
