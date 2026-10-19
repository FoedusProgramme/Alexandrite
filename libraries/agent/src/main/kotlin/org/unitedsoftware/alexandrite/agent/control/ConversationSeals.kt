package org.unitedsoftware.alexandrite.agent.control

import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.di.ContributedSpi

/** What the agent keeps for each active conversation and forgets once it is sealed. */
@ContributedSpi
internal interface ConversationSeals {
    /** Forgets what this keeps for [conversation], which was sealed. */
    suspend fun sealed(conversation: ConversationId)
}
