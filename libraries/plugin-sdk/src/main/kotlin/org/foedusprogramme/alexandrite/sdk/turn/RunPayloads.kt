package org.foedusprogramme.alexandrite.sdk.turn

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart

/** The lifecycle of a delegated run under [turn]. */
@Poko
public class AgentRunLifecycle(
    public val turn: TurnInfo,
    public val run: RunId,
    public val profile: AgentId,
    public val background: Boolean,
)

/** The lifecycle of a background call of [turn]. */
@Poko
public class JobLifecycle(public val turn: TurnInfo, public val run: RunId, public val call: ToolCallPart)
