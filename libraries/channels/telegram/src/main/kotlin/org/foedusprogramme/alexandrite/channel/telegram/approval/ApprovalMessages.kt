package org.foedusprogramme.alexandrite.channel.telegram.approval

import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped

/** The messages that showed the latest approval requests, by Telegram chat and message id. */
@ChannelInstanceScoped
internal class ApprovalMessages {
    private val requests = object : LinkedHashMap<Pair<Long, Long>, Request>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Long, Long>, Request>) = size > KEPT
    }

    fun note(chat: Long, message: Long, request: Request) {
        synchronized(requests) { requests[chat to message] = request }
    }

    /** The line that stands for the message [message] of [chat] in a quote, null when it shows no noted request. */
    fun quoted(chat: Long, message: Long): String? {
        val request = synchronized(requests) { requests[chat to message] } ?: return null
        return "Alexandrite's approval request ${request.id} for ${request.tool} (${request.state})"
    }

    /** A request as its messages show it. */
    class Request(val id: String, val tool: String) {
        /** How the request stands, as a quote of one of its messages tells. */
        @Volatile
        var state: String = "waiting"
    }

    private companion object {
        const val KEPT = 1024
    }
}
