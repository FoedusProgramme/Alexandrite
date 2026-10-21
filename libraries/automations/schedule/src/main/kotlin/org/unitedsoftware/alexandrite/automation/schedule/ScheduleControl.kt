package org.unitedsoftware.alexandrite.automation.schedule

import dev.drewhamilton.poko.Poko
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.automation.common.InternalAutomationApi
import org.unitedsoftware.alexandrite.automation.common.When
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.tool.Mandate
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Makes, lists and cancels the scheduled tasks of each agent chat key, for the tools, the command and a host's UI. */
@InternalAutomationApi
@Singleton
public class ScheduleControl internal constructor(
    private val config: ScheduleConfig,
    private val tasks: TaskRecords,
    private val scheduling: Scheduling,
) {
    private val lock = Mutex()
    private val random = SecureRandom()

    /** The shortest interval a recurring task may repeat at. */
    public val minInterval: Duration get() = config.minIntervalMinutes.minutes

    /** The time the schedule runs on. */
    public fun now(): Instant = scheduling.time.now()

    /**
     * Makes a task of [key] that runs [prompt] in [chat] when [schedule] in [zone] comes due, as the operator of
     * [mandate], or as nobody when it is null. The task takes [mandate] over and revokes it when it ends or cannot be
     * made.
     *
     * @throws ScheduleRefusedException when the task may not be made, with the reason
     */
    public suspend fun create(
        key: AgentChatKey,
        chat: ChatAddress,
        schedule: When,
        zone: ZoneId,
        prompt: String,
        by: ChatUser,
        mandate: Mandate?,
    ): ScheduledTask = revokedOnFailure(mandate) {
        val text = prompt.trim()
        if (text.isEmpty()) refuse("The prompt is empty.")
        if (text.length > MAX_PROMPT) refuse("The prompt has more than $MAX_PROMPT characters.")
        tasks.awaitLoaded()
        lock.withLock {
            val all = tasks.all().map { it.second }
            if (all.count { it.key == key } >= config.maxPerChat) {
                refuse("This chat has ${config.maxPerChat} tasks, as many as it may: cancel one first.")
            }
            if (all.count { it.key.agent == key.agent } >= config.maxPerAgent) {
                refuse("Agent ${key.agent} has ${config.maxPerAgent} tasks, as many as it may: cancel one first.")
            }
            val now = now()
            check(schedule, zone, now)
            val id = newId()
            val record = TaskRecord(
                key = key,
                chat = chat,
                schedule = ScheduleForm.of(schedule),
                zone = zone.id,
                prompt = text,
                createdBy = by.address,
                creatorName = by.displayName,
                createdAt = "$now",
                mandate = mandate,
            )
            tasks.prepare(id, record)
            val task = try {
                scheduling.scheduler().schedule(id, schedule, zone)
            } catch (e: IllegalArgumentException) {
                tasks.discard(id)
                refuse("It is not due after now.")
            } catch (e: Throwable) {
                tasks.discard(id)
                throw e
            }
            logger.info("Task {} of {} made by {}: next {}", id, key, by.address, task.next)
            view(id, record.with(task))
        }
    }

    /** The tasks of [key], in the order they were made. */
    public suspend fun list(key: AgentChatKey): List<ScheduledTask> {
        tasks.awaitLoaded()
        return tasks.all().filter { it.second.key == key }.map { (id, record) -> view(id, record) }
    }

    /** Cancels task [id] of [key] for [by], null for the host, and returns false when [key] has no such task. */
    public suspend fun cancel(key: AgentChatKey, id: String, by: ChatUser?): Boolean {
        tasks.awaitLoaded()
        if (tasks.live(id)?.key != key) return false
        val cancelled = scheduling.scheduler().cancel(id)
        if (cancelled) logger.info("Task {} of {} cancelled by {}", id, key, by?.address ?: "the host")
        return cancelled
    }

    private fun check(schedule: When, zone: ZoneId, now: Instant) {
        if (schedule.next(now, zone) == null) refuse("It is not due after now.")
        val minimum = minInterval
        when (schedule) {
            is When.Every -> if (schedule.interval < minimum) {
                refuse("A task may repeat at most once every ${shown(minimum)}, not every ${shown(schedule.interval)}.")
            }

            is When.Cron -> gapUnder(minimum, schedule.expression, zone, now)?.let { gap ->
                refuse(
                    "Cron ${schedule.expression} fires ${shown(gap)} apart at times; a task may repeat at most once " +
                        "every ${shown(minimum)}.",
                )
            }

            is When.Once -> Unit
        }
    }

    private fun newId(): String {
        while (true) {
            val id = "t-" + String(CharArray(ID_LENGTH) { ID_ALPHABET[random.nextInt(ID_ALPHABET.length)] })
            if (tasks.find(id) == null) return id
        }
    }

    /** What [block] returns, with [mandate] revoked when it fails. */
    private suspend inline fun <T> revokedOnFailure(mandate: Mandate?, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        if (mandate != null) withRevoked(mandate)
        throw e
    }

    private suspend fun withRevoked(mandate: Mandate) {
        try {
            tasks.revoke(mandate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Cannot revoke mandate {} of a task not made: {}", mandate, e.toString())
        }
    }

    private fun view(id: String, record: TaskRecord): ScheduledTask = ScheduledTask(
        id,
        record.key,
        record.chat,
        record.schedule.toWhen(),
        record.zoneId,
        record.prompt,
        record.createdBy,
        record.creatorName,
        record.createdInstant,
        record.mandate,
        record.nextInstant,
        record.pending != null,
    )

    private companion object {
        const val MAX_PROMPT = 4000
        const val ID_LENGTH = 4
        const val ID_ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"
    }
}

/** A scheduled task as [ScheduleControl] shows it. */
@InternalAutomationApi
@Poko
public class ScheduledTask(
    /** Such as `t-4k2p` */
    public val id: String,
    public val key: AgentChatKey,
    /** Where the task's turns answer */
    public val chat: ChatAddress,
    public val schedule: When,
    /** The zone [schedule]'s local times are read in */
    public val zone: ZoneId,
    public val prompt: String,
    public val createdBy: UserAddress,
    public val creatorName: String,
    public val createdAt: Instant,
    /** Null for a task whose turns run as nobody */
    public val mandate: Mandate?,
    /** Null once nothing is due */
    public val next: Instant?,
    /** Whether an occurrence fired and has not ended yet */
    public val running: Boolean,
)

/** A task that [ScheduleControl] may not make, for the reason in [message]. */
@InternalAutomationApi
public class ScheduleRefusedException(message: String) : Exception(message)

private fun refuse(reason: String): Nothing = throw ScheduleRefusedException(reason)

private val logger = LoggerFactory.getLogger(ScheduleControl::class.java)
