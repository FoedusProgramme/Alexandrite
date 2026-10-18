package org.unitedsoftware.alexandrite.agent.profile

import org.unitedsoftware.alexandrite.sdk.agent.AgentProfile
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Provides

internal class GeneralProfile :
    AgentProfile(
        AgentId("general"),
        "General",
        "A general-purpose sub-agent with your tools, model and settings: for research, searches across many files, " +
            "or any self-contained task you want done outside your own context.",
        "Carry out the task you are given with the tools you have. Report back what you did, what you found and " +
            "anything left undone, concisely; the delegating agent sees only your final reply.",
    )

@Provides
@Contribute(AgentProfile::class)
internal fun generalProfile(): GeneralProfile = GeneralProfile()
