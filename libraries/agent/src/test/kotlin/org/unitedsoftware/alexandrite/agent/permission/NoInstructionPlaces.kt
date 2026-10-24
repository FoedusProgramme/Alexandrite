package org.unitedsoftware.alexandrite.agent.permission

import org.unitedsoftware.alexandrite.agent.trust.TaintLedger
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import java.nio.file.Path

internal object NoInstructionPlaces : InstructionPlaces {
    override fun kind(path: Path, agent: AgentId): String? = null

    override fun sandboxPaths(): List<Path> = emptyList()
}

internal object NoTaintLedger : TaintLedger {
    override suspend fun wrote(path: Path, reason: String) = Unit

    override fun reason(path: Path): String? = null
}

/** Edit places that hold every file. */
internal val EveryEditPlace = EditPlaces { _, _ -> true }
