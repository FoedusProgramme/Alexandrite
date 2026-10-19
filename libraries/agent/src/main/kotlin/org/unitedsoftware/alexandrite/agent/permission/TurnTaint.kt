package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
    /** The marks that could not be stored, which later turns of the conversation still count. */
    private val unstored: UnstoredTaints? = null,
    /** A mark an earlier turn could not store, which this turn stores. */
    carried: ConversationTaint? = null,
) {
    @Volatile
    var tainted: Boolean = recorded != null || carried != null || unread
        private set

    private val first = AtomicReference(recorded ?: carried)
    private val own = AtomicReference<ConversationTaint?>(null)
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** Whether the turn stored [known], or read it from the store at its start. */
    @Volatile
    var stored: Boolean = recorded != null
        private set

    private val writing = Mutex()

    /** The conversation's first mark as the turn knows it, null while it knows none. */
    val known: ConversationTaint? get() = first.get()

    /** The first untrusted content the turn took in itself, null when it only read or took over a mark. */
    val noted: ConversationTaint? get() = own.get()

    /** Counts the turn as tainted for [reason], content it took in itself, for [record] to store. */
    fun note(reason: String) {
        val taint = taint(reason)
        own.compareAndSet(null, taint)
        listeners.forEach { it(taint.reason) }
    }

    /** Counts the turn as tainted for [reason], a mark it takes over from the turn above it, for [record] to store. */
    fun carry(reason: String) {
        taint(reason)
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
            if (write(taint)) stored = true
        }
    }

    /**
     * Writes [reason] as the conversation's mark unless it has one, whatever the turn stored before, for content that a
     * background job of the turn took in after the turn ended.
     */
    suspend fun recordLate(reason: String) {
        write(ConversationTaint(clock.instant(), cut(oneLine(reason), MAX_REASON_CHARS)))
    }

    private fun taint(reason: String): ConversationTaint {
        val taint = ConversationTaint(clock.instant(), cut(oneLine(reason), MAX_REASON_CHARS))
        first.compareAndSet(null, taint)
        tainted = true
        logger.debug("Turn {} of {} is tainted: {}", turn, key, taint.reason)
        return taint
    }

    /** Whether [taint] is the conversation's mark now; when it cannot be written, the later turns count it. */
    private suspend fun write(taint: ConversationTaint): Boolean {
        val store = store ?: return false
        val conversation = conversation ?: return false
        return try {
            val wrote = store.markTainted(conversation, taint.reason, taint.at)
            unstored?.stored(conversation)
            if (wrote) logger.info("Conversation {} of {} is tainted: {}", conversation, key, taint.reason)
            true
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            currentCoroutineContext().ensureActive()
            unstored?.failed(conversation, taint)
            logger.warn(
                "Conversation {} of {} could not be marked tainted, so it counts as tainted until the agent stops: {}",
                conversation,
                key,
                e.toString(),
            )
            false
        }
    }
}

/** The marks of conversations that could not be stored, kept until a later write stores them or the agent stops. */
@Singleton
internal class UnstoredTaints {
    private val marks = ConcurrentHashMap<ConversationId, ConversationTaint>()

    operator fun get(conversation: ConversationId): ConversationTaint? = marks[conversation]

    fun failed(conversation: ConversationId, taint: ConversationTaint) {
        marks.putIfAbsent(conversation, taint)
    }

    fun stored(conversation: ConversationId) {
        marks.remove(conversation)
    }

    /** Forgets the mark of [conversation], which a person vouched for, and returns it. */
    fun cleared(conversation: ConversationId): ConversationTaint? = marks.remove(conversation)
}

/** Reads at the start of each turn whether its conversation took in untrusted content before. */
@Singleton
internal class ConversationTaints(
    private val store: ConversationStore,
    private val clock: Clock,
    private val unstored: UnstoredTaints,
) {
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
            return TurnTaint(turn, key, clock, conversation, store, unread = true, unstored = unstored)
        }
        val carried = if (recorded == null) unstored[conversation] else null
        return TurnTaint(turn, key, clock, conversation, store, recorded, unstored = unstored, carried = carried)
    }

    /** The mark of [conversation], one that could not be stored included; throws when it cannot be read. */
    suspend fun mark(conversation: ConversationId): ConversationTaint? =
        store.taint(conversation) ?: unstored[conversation]
}

/** The characters of a reason that a mark keeps. */
private const val MAX_REASON_CHARS = 300

private val logger = LoggerFactory.getLogger(TurnTaint::class.java)
