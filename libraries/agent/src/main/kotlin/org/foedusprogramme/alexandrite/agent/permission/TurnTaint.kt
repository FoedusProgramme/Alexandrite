package org.foedusprogramme.alexandrite.agent.permission

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.foedusprogramme.alexandrite.agent.prompt.oneLine
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.store.ConversationTaint
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

/** Whether the turn [turn] of [key] or its conversation [conversation] took in untrusted content. */
internal class TurnTaint(
    private val turn: TurnId,
    private val key: AgentChatKey,
    private val clock: Clock = Clock.systemUTC(),
    /** Null to keep the taint in memory only. */
    private val conversation: ConversationId? = null,
    private val store: ConversationStore? = null,
    recorded: ConversationTaint? = null,
    /** Whether the conversation's mark could not be read. */
    val unread: Boolean = false,
) {
    @Volatile
    var tainted: Boolean = recorded != null || unread
        private set

    private val first = AtomicReference(recorded)

    /** Whether [known] is in the store. */
    @Volatile
    var stored: Boolean = recorded != null
        private set

    private val writing = Mutex()

    /** The conversation's first mark as the turn knows it, null while it knows none. */
    val known: ConversationTaint? get() = first.get()

    /** Counts the turn as tainted for [reason], for [record] to store. */
    fun note(reason: String) {
        val line = cut(oneLine(reason))
        first.compareAndSet(null, ConversationTaint(clock.instant(), line))
        tainted = true
        logger.debug("Turn {} of {} is tainted: {}", turn, key, line)
    }

    /** Counts the turn as tainted for [reason] and writes the mark to the store. */
    suspend fun mark(reason: String) {
        note(reason)
        record()
    }

    /** Stores the first reason the turn noted as the conversation's mark, once. */
    suspend fun record() {
        val store = store ?: return
        val conversation = conversation ?: return
        writing.withLock {
            if (stored) return
            val taint = first.get() ?: return
            try {
                val wrote = store.markTainted(conversation, taint.reason, taint.at)
                stored = true
                if (wrote) logger.info("Conversation {} of {} is tainted: {}", conversation, key, taint.reason)
            } catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                currentCoroutineContext().ensureActive()
                logger.warn(
                    "Conversation {} of {} could not be marked tainted, so only turn {} counts it as tainted: {}",
                    conversation,
                    key,
                    turn,
                    e.toString(),
                )
            }
        }
    }
}

/** Reads at the start of each turn whether its conversation took in untrusted content before. */
@Singleton
internal class ConversationTaints(private val store: ConversationStore, private val clock: Clock) {
    /** The taint of the turn [turn] of [key] in [conversation], set when its mark cannot be read. */
    suspend fun open(turn: TurnId, key: AgentChatKey, conversation: ConversationId): TurnTaint {
        val recorded = try {
            store.taint(conversation)
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            currentCoroutineContext().ensureActive()
            logger.warn(
                "The taint of conversation {} of {} could not be read, so turn {} counts as tainted: {}",
                conversation,
                key,
                turn,
                e.toString(),
            )
            return TurnTaint(turn, key, clock, conversation, store, unread = true)
        }
        return TurnTaint(turn, key, clock, conversation, store, recorded)
    }
}

/** [text] up to [MAX_REASON_CHARS] characters, without splitting a surrogate pair. */
private fun cut(text: String): String {
    if (text.length <= MAX_REASON_CHARS) return text
    val end = if (text[MAX_REASON_CHARS - 1].isHighSurrogate()) MAX_REASON_CHARS - 1 else MAX_REASON_CHARS
    return text.substring(0, end)
}

/** The characters of a reason that a mark keeps. */
private const val MAX_REASON_CHARS = 300

private val logger = LoggerFactory.getLogger(TurnTaint::class.java)
