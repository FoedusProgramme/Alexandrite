package org.unitedsoftware.alexandrite.agent.workspace

import org.unitedsoftware.alexandrite.agent.prompt.PromptContributor
import org.unitedsoftware.alexandrite.agent.prompt.PromptOrder
import org.unitedsoftware.alexandrite.agent.prompt.PromptTurn
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.PromptSection
import java.nio.file.Path

internal const val WORKSPACE_SECTION: String = "workspace"

/** Where an agent offered tools keeps its own files. */
@Singleton
@Contribute(PromptContributor::class)
internal class WorkspacePrompt(private val workspaces: Workspaces) : PromptContributor {
    override val order: Int = PromptOrder.WORKSPACE

    override fun section(turn: PromptTurn): PromptSection? =
        workspaces.paths[turn.info.agent]?.takeIf { turn.offered.isNotEmpty() }?.let(::workspaceSection)
}

/** Where an agent keeps its own files, at [workspace]. */
internal fun workspaceSection(workspace: Path): PromptSection = PromptSection(
    WORKSPACE_SECTION,
    listOf(
        "# Workspace",
        "Your workspace is the directory $workspace, where your own files belong. File tools resolve a relative " +
            "path against it; give any other place as an absolute path.",
    ).joinToString("\n\n"),
    stable = true,
)
