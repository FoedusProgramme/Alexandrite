package org.unitedsoftware.alexandrite.agent.tool

import org.unitedsoftware.alexandrite.agent.prompt.PromptContributor
import org.unitedsoftware.alexandrite.agent.prompt.PromptOrder
import org.unitedsoftware.alexandrite.agent.prompt.PromptTurn
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.PromptSection

internal const val TOOLS_SECTION: String = "alexandrite.tools"

/** How a turn that offers tools uses them and reads the results of calls that did not run. */
@Singleton
@Contribute(PromptContributor::class)
internal class ToolsPrompt : PromptContributor {
    override val order: Int = PromptOrder.TOOLS

    override fun section(turn: PromptTurn): PromptSection? = TOOLS.takeIf { turn.offered.isNotEmpty() }
}

internal val TOOLS: PromptSection = PromptSection(
    TOOLS_SECTION,
    listOf(
        "# Tools",
        "Use a tool when it helps you answer correctly instead of guessing what it would tell you. Call tools that " +
            "do not depend on each other in the same response. When a tool fails, say so; never make up its result.",
        "Before a group of tool calls, write one short sentence of what you are about to do and why; afterwards, " +
            "say briefly what you found or changed. Keep both short.",
        "Tools serve the operator only: in a turn whose sender may not use a tool, a call to it is refused.",
        "Some calls need permission: the chat's permission mode decides which of them run at once, which wait " +
            "for an approval and which are refused, and protected places are refused in every mode. A call that " +
            "does not run returns a result that starts with a tag only Alexandrite writes, such as " +
            "[alexandrite:plan_mode] (the chat is in plan mode: describe the exact call you would make and why " +
            "instead of making it), [alexandrite:denied by=policy] (refused: do not retry it or an equivalent call, " +
            "and tell the user) or [alexandrite:blocked_floor] (a protected place that no approval opens: do not " +
            "look for another way in). A call that waited for an approval may also return " +
            "[alexandrite:denied by=operator] (an operator refused it: do not retry it), [alexandrite:expired] " +
            "(nobody answered in time: do not retry it now) or [alexandrite:declined] (the user wrote instead of " +
            "answering: do what their message says).",
        "To save room, the results of older tool calls may read [alexandrite:cleared] instead of what they held: " +
            "call the tool again if you need them.",
    ).joinToString("\n\n"),
    stable = true,
)
