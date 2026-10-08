package org.foedusprogramme.alexandrite.testkit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.InitiatedTurn
import org.foedusprogramme.alexandrite.sdk.turn.RefusalReason
import org.foedusprogramme.alexandrite.sdk.turn.Submission
import org.foedusprogramme.alexandrite.sdk.turn.TurnInitiator
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnSubmitter
import org.foedusprogramme.alexandrite.sdk.turn.TurnTicket

/** A turn whose outcome the test decides. */
public class TestTurnTicket(override val turn: TurnId = TurnId("test-turn")) : TurnTicket {
    private val result = CompletableDeferred<TurnOutcome>()

    @Volatile
    private var cancelledByTicket = false

    /** Whether [cancel] ended the turn. */
    public val cancelled: Boolean get() = cancelledByTicket

    public val ended: Boolean get() = result.isCompleted

    /** Ends the turn with [outcome], and returns whether it had not ended. */
    public fun complete(outcome: TurnOutcome): Boolean = result.complete(outcome)

    override suspend fun outcome(): TurnOutcome = result.await()

    /** Ends the turn as cancelled. */
    override fun cancel(): Boolean = result.complete(TurnOutcome.Cancelled).also { if (it) cancelledByTicket = true }
}

/**
 * A [TurnSubmitter] that records the submissions and accepts each one as a [TestTurnTicket] `test-turn-<n>`, unless a
 * refusal is queued.
 */
public class RecordingTurnSubmitter(
    /** The outcome every accepted turn ends with at once, null to leave the turns to the test. */
    outcome: TurnOutcome? = null,
) : TurnSubmitter {
    private val admissions = Admissions<Submission>(outcome)

    public val submissions: List<Submission> get() = admissions.items

    /** The admission of each submission, in order. */
    public val results: List<Admission> get() = admissions.results

    public val tickets: List<TestTurnTicket> get() = admissions.tickets

    /** Refuses the next submission for [reason]. */
    public fun refuseNext(reason: RefusalReason, queueCapacity: Int? = null) {
        admissions.refuseNext(Admission.Refused(reason, queueCapacity))
    }

    /** Suspends until [count] submissions were made, and returns them. */
    public suspend fun awaitSubmissions(count: Int): List<Submission> = admissions.await(count)

    override fun submit(submission: Submission): Admission = admissions.admit(submission)
}

/**
 * A [TurnInitiator] of the plugin [plugin] that records the initiated turns and admits them as a
 * [RecordingTurnSubmitter] admits submissions.
 */
public class RecordingTurnInitiator(
    private val plugin: String = "test",
    /** The outcome every accepted turn ends with at once, null to leave the turns to the test. */
    outcome: TurnOutcome? = null,
) : TurnInitiator {
    private val admissions = Admissions<Initiated>(outcome)

    public val initiated: List<Initiated> get() = admissions.items

    /** The admission of each initiated turn, in order. */
    public val results: List<Admission> get() = admissions.results

    public val tickets: List<TestTurnTicket> get() = admissions.tickets

    /** Refuses the next turn for [reason]. */
    public fun refuseNext(reason: RefusalReason, queueCapacity: Int? = null) {
        admissions.refuseNext(Admission.Refused(reason, queueCapacity))
    }

    /** Suspends until [count] turns were initiated, and returns them. */
    public suspend fun awaitInitiated(count: Int): List<Initiated> = admissions.await(count)

    override fun initiate(turn: InitiatedTurn): Admission = record(plugin, turn)

    internal fun record(plugin: String, turn: InitiatedTurn): Admission = admissions.admit(Initiated(plugin, turn))

    /** A [turn] that the plugin [plugin] started. */
    public data class Initiated(public val plugin: String, public val turn: InitiatedTurn)
}

private class Admissions<T>(private val outcome: TurnOutcome?) {
    private val lock = Any()
    private val refusals = ArrayDeque<Admission.Refused>()
    private val entries = MutableStateFlow<List<Pair<T, Admission>>>(emptyList())

    val items: List<T> get() = entries.value.map { it.first }

    val results: List<Admission> get() = entries.value.map { it.second }

    val tickets: List<TestTurnTicket> get() = results.mapNotNull {
        (it as? Admission.Accepted)?.ticket as? TestTurnTicket
    }

    fun refuseNext(refusal: Admission.Refused) {
        synchronized(lock) { refusals.addLast(refusal) }
    }

    fun admit(item: T): Admission = synchronized(lock) {
        val admission = refusals.removeFirstOrNull() ?: Admission.Accepted(
            TestTurnTicket(TurnId("test-turn-${entries.value.count { it.second is Admission.Accepted } + 1}"))
                .also { ticket -> outcome?.let(ticket::complete) },
        )
        entries.value += item to admission
        admission
    }

    suspend fun await(count: Int): List<T> = entries.first { it.size >= count }.take(count).map { it.first }
}
