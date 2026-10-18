package org.unitedsoftware.alexandrite.automation.schedule

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.automation.common.CronExpression
import org.unitedsoftware.alexandrite.automation.common.Occurrence
import org.unitedsoftware.alexandrite.automation.common.Pending
import org.unitedsoftware.alexandrite.automation.common.Task
import org.unitedsoftware.alexandrite.automation.common.TaskStore
import org.unitedsoftware.alexandrite.automation.common.When
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.turn.Mandate
import org.unitedsoftware.alexandrite.sdk.turn.TurnInitiator
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The tasks in the plugin's records as the scheduler's [TaskStore], which revokes a task's mandate on removal. */
@Singleton
internal class TaskRecords(records: Records, private val initiator: TurnInitiator) : TaskStore<String> {
    private val collection = records.collection<TaskRecord>(COLLECTION)
    private val live = LinkedHashMap<String, TaskRecord>()
    private val drafts = ConcurrentHashMap<String, TaskRecord>()
    private val gone = object : LinkedHashMap<String, TaskRecord>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TaskRecord>): Boolean = size > GONE
    }
    private val loaded = MutableStateFlow(false)

    /** Suspends until the records were first loaded. */
    suspend fun awaitLoaded() {
        loaded.first { it }
    }

    /** The tasks, in the order they were made. */
    fun all(): List<Pair<String, TaskRecord>> = synchronized(live) { live.map { it.key to it.value } }

    /** Task [id], or one removed lately, null when there is neither. */
    fun find(id: String): TaskRecord? = live(id) ?: drafts[id] ?: synchronized(gone) { gone[id] }

    /** Task [id], null when there is none. */
    fun live(id: String): TaskRecord? = synchronized(live) { live[id] }

    /** Keeps [record] for task [id] until the scheduler saves it. */
    fun prepare(id: String, record: TaskRecord) {
        drafts[id] = record
    }

    fun discard(id: String) {
        drafts.remove(id)
    }

    override suspend fun load(): List<Task<String>> {
        val tasks = mutableListOf<Task<String>>()
        val read = LinkedHashMap<String, TaskRecord>()
        for (stored in collection.list()) {
            val task = try {
                stored.value.task(stored.key)
            } catch (e: IllegalArgumentException) {
                logger.warn("Skipping scheduled task {}, which cannot be read: {}", stored.key, e.message)
                continue
            }
            read[stored.key] = stored.value
            tasks += task
        }
        synchronized(live) {
            live.clear()
            live.putAll(read)
        }
        loaded.value = true
        return tasks
    }

    override suspend fun save(task: Task<String>) {
        val details = checkNotNull(live(task.id) ?: drafts[task.id]) { "Task ${task.id} has no record to save into." }
        val record = details.with(task)
        collection.put(task.id, record, record.by)
        synchronized(live) { live[task.id] = record }
        drafts.remove(task.id)
    }

    override suspend fun remove(id: String) {
        collection.remove(id)
        val record = synchronized(live) { live.remove(id) } ?: drafts.remove(id) ?: return
        synchronized(gone) { gone[id] = record }
        record.mandate?.let { revokeQuietly(id, it) }
    }

    suspend fun revoke(mandate: Mandate) {
        initiator.revoke(mandate)
    }

    private suspend fun revokeQuietly(id: String, mandate: Mandate) {
        try {
            revoke(mandate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Cannot revoke the mandate of task {}: {}", id, e.toString())
        }
    }

    private companion object {
        const val COLLECTION = "tasks"
        const val GONE = 256
    }
}

/** A task as the records keep it, times as ISO-8601 text. */
@Serializable
internal data class TaskRecord(
    val key: AgentChatKey,
    /** Where the task answers */
    val chat: ChatAddress,
    val schedule: ScheduleForm,
    val zone: String,
    val prompt: String,
    val by: UserAddress,
    val byName: String,
    val createdAt: String,
    /** Null for a task whose turns run as nobody */
    val mandate: Mandate? = null,
    val next: String? = null,
    val pending: PendingForm? = null,
) {
    val zoneId: ZoneId get() = ZoneId.of(zone)

    val createdInstant: Instant get() = Instant.parse(createdAt)

    val nextInstant: Instant? get() = next?.let(Instant::parse)

    /** Throws [IllegalArgumentException] when a field cannot be read. */
    fun task(id: String): Task<String> = try {
        Instant.parse(createdAt)
        Task(id, schedule.toWhen(), zoneId, nextInstant, pending?.toPending())
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: RuntimeException) {
        throw IllegalArgumentException(e.message, e)
    }

    fun with(task: Task<String>): TaskRecord = copy(
        next = task.next?.toString(),
        pending = task.pending?.let(PendingForm::of),
    )
}

@Serializable
internal sealed interface ScheduleForm {
    fun toWhen(): When

    @Serializable
    @SerialName("once")
    data class Once(val at: String) : ScheduleForm {
        override fun toWhen(): When = When.Once(LocalDateTime.parse(at))
    }

    @Serializable
    @SerialName("every")
    data class Every(val seconds: Long, val from: String) : ScheduleForm {
        override fun toWhen(): When = When.Every(seconds.seconds, Instant.parse(from))
    }

    @Serializable
    @SerialName("cron")
    data class Cron(val expression: String) : ScheduleForm {
        override fun toWhen(): When = When.Cron(CronExpression.parse(expression))
    }

    companion object {
        fun of(schedule: When): ScheduleForm = when (schedule) {
            is When.Once -> Once(schedule.at.toString())
            is When.Every -> Every(schedule.interval.inWholeSeconds, schedule.from.toString())
            is When.Cron -> Cron(schedule.expression.describe())
        }
    }
}

@Serializable
internal data class PendingForm(val due: String, val lateMillis: Long, val replay: Boolean) {
    fun toPending(): Pending = Pending(Occurrence(Instant.parse(due), lateMillis.milliseconds), replay)

    companion object {
        fun of(pending: Pending): PendingForm =
            PendingForm(pending.occurrence.due.toString(), pending.occurrence.late.inWholeMilliseconds, pending.replay)
    }
}

private val logger = LoggerFactory.getLogger(TaskRecords::class.java)
