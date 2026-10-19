package org.unitedsoftware.alexandrite.agent.permission

import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import java.nio.file.Path
import java.time.Instant

internal object NoInstructionPlaces : InstructionPlaces {
    override fun kind(path: Path, agent: AgentId): String? = null

    override fun sandboxPaths(): List<Path> = emptyList()
}

internal object NoTaintLedger : TaintLedger {
    override suspend fun wrote(preview: ReviewPreview, from: Instant, to: Instant, reason: String) = Unit

    override fun reason(path: Path): String? = null
}
