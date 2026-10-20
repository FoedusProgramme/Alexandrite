package org.unitedsoftware.alexandrite.agent.control

import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.di.ContributedSpi
import org.unitedsoftware.alexandrite.sdk.store.ConversationState
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.RecordCollection

/** What the agent keeps for each active conversation and forgets once it is sealed. */
@ContributedSpi
internal interface ConversationSeals {
    /** Forgets what this keeps for [conversation], which was sealed. */
    suspend fun sealed(conversation: ConversationId)
}

/** Removes the records, keyed by conversation, whose conversation is not active. */
internal suspend fun RecordCollection<*>.pruneInactive(conversations: ConversationStore) {
    for (record in list()) {
        val state = conversations.conversation(ConversationId(record.key))?.state
        if (state != ConversationState.ACTIVE) remove(record.key)
    }
}
