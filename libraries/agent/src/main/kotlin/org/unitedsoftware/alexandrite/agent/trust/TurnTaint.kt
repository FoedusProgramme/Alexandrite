package org.unitedsoftware.alexandrite.agent.trust

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.prompt.oneLine
import org.unitedsoftware.alexandrite.internal.text.cut
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Whether the turn [turn] of [key] or its conversation [conversation] took in untrusted content. A mark that cannot be
 * stored fails the caller, as the conversation's store is then broken.
 */
internal class TurnTaint(
    private val turn: TurnId,
    private val key: AgentChatKey,
    private val clock: Clock = Clock.systemUTC(),
    /** Null to keep the taint in memory only. */
    private val conversation: ConversationId? = null,
    private val store: ConversationStore? = null,
    recorded: ConversationTaint? = null,
) {
    private val first = AtomicReference(recorded)
    private val own = AtomicReference<ConversationTaint?>(null)
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** Whether the turn stored [known], or read it from the store at its start. */
    @Volatile
    private var stored: Boolean = recorded != null

    private val writing = Mutex()

    val tainted: Boolean get() = first.get() != null

    /** The conversation whose mark the turn writes, null when it keeps it in memory only. */
    val conversationId: ConversationId? get() = conversation

    /** The conversation's first mark as the turn knows it, null while it knows none. */
    val known: ConversationTaint? get() = first.get()

    /** Counts the turn as tainted for [reason], content it took in itself, for [record] to store. */
    fun note(reason: String) {
        val taint = ConversationTaint(clock.instant(), cut(oneLine(reason), MAX_REASON_CHARS))
        first.compareAndSet(null, taint)
        logger.debug("Turn {} of {} is tainted: {}", turn, key, taint.reason)
        own.compareAndSet(null, taint)
        listeners.forEach { it(taint.reason) }
    }

    /** Has [listener] told the reason of each untrusted content the turn takes in itself, the first one at once. */
    fun onNoted(listener: (String) -> Unit) {
        listeners += listener
        own.get()?.let { listener(it.reason) }
    }

    /** Counts the turn as tainted for [reason] and writes the mark to the store. */
    suspend fun mark(reason: String) {
        note(reason)
        record()
    }

    /** Stores the first reason the turn noted as the conversation's mark, once. */
    suspend fun record() {
        store ?: return
        conversation ?: return
        writing.withLock {
            if (stored) return
            val taint = first.get() ?: return
            write(taint)
            stored = true
        }
    }

    /**
     * Writes [reason] as the conversation's mark unless it has one, whatever the turn stored before, for content that a
     * background job of the turn took in after the turn ended.
     */
    suspend fun recordLate(reason: String) {
        write(ConversationTaint(clock.instant(), cut(oneLine(reason), MAX_REASON_CHARS)))
    }

    private suspend fun write(taint: ConversationTaint) {
        val store = store ?: return
        val conversation = conversation ?: return
        if (store.markTainted(conversation, taint.reason, taint.at)) {
            logger.info("Conversation {} of {} is tainted: {}", conversation, key, taint.reason)
        }
    }
}

/** Reads at the start of each turn whether its conversation took in untrusted content before. */
@Singleton
internal class ConversationTaints(private val store: ConversationStore, private val clock: Clock) {
    /** The taint of the turn [turn] of [key] in [conversation]; a mark that cannot be read fails the turn. */
    suspend fun open(turn: TurnId, key: AgentChatKey, conversation: ConversationId): TurnTaint =
        TurnTaint(turn, key, clock, conversation, store, store.taint(conversation))
}

/** The characters of a reason that a mark keeps. */
private const val MAX_REASON_CHARS = 300

private val logger = LoggerFactory.getLogger(TurnTaint::class.java)
