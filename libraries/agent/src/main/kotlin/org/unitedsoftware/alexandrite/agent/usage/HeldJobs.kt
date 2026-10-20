package org.unitedsoftware.alexandrite.agent.usage

import org.unitedsoftware.alexandrite.agent.job.EndedJob
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.di.Singleton

/** The results of jobs whose turns were refused past a budget, kept for the next turn that runs in their conversation. */
@Singleton
internal class HeldJobs {
    private val held = HashMap<ConversationId, MutableList<EndedJob>>()

    fun hold(job: EndedJob) {
        synchronized(held) { held.getOrPut(job.job.resultConversation) { mutableListOf() } += job }
    }

    /** Takes the results held for [conversation], in the order they ended. */
    fun take(conversation: ConversationId): List<EndedJob> = synchronized(held) { held.remove(conversation).orEmpty() }
}
