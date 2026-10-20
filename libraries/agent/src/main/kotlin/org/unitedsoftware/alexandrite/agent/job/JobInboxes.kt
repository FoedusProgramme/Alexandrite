package org.unitedsoftware.alexandrite.agent.job

import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import java.util.concurrent.ConcurrentHashMap

/** A running turn that takes the results of its conversation's jobs itself, as a sub-agent run does. */
internal interface JobInbox {
    /** Takes the result of [job], false when the turn takes no more. */
    fun offer(job: EndedJob): Boolean

    /** Tells the turn that a job of its conversation is gone, with or without a result. */
    fun ended()
}

/** The turns that take their jobs' results themselves, by their conversation. */
@Singleton
internal class JobInboxes {
    private val inboxes = ConcurrentHashMap<ConversationId, JobInbox>()

    fun open(conversation: ConversationId, inbox: JobInbox) {
        inboxes[conversation] = inbox
    }

    fun close(conversation: ConversationId) {
        inboxes.remove(conversation)
    }

    operator fun get(conversation: ConversationId): JobInbox? = inboxes[conversation]
}
