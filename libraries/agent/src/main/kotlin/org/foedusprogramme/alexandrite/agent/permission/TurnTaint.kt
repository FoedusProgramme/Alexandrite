package org.foedusprogramme.alexandrite.agent.permission

import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.slf4j.LoggerFactory

/** Whether the turn [turn] of [key] took in untrusted content. */
internal class TurnTaint(private val turn: TurnId, private val key: AgentChatKey) {
    @Volatile
    var tainted: Boolean = false
        private set

    fun mark(reason: String) {
        tainted = true
        logger.debug("Turn {} of {} is tainted: {}", turn, key, reason)
    }
}

private val logger = LoggerFactory.getLogger(TurnTaint::class.java)
