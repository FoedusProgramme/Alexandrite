package org.foedusprogramme.alexandrite.runtime.lifecycle

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.plugin.PluginScope
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark

private val logger: Logger = LoggerFactory.getLogger(AlexandriteRuntime::class.java)

/** How long cancelled coroutines get to finish even when the shutdown deadline has passed. */
private val SETTLE_TIME = 100.milliseconds

/** The [PluginScope]s of a runtime, children of its coroutine scope [runtime], and the scopes of channel instances. */
internal class PluginScopes(
    private val name: String,
    private val redactor: Redactor,
    private val runtime: CoroutineContext,
) {
    private val scopes = CopyOnWriteArrayList<Scope>()

    /** The jobs of the instance scopes already reported as still running. */
    private val abandoned = ConcurrentHashMap.newKeySet<Job>()

    fun create(plugin: String): PluginScope {
        val failed = CoroutineExceptionHandler { _, error ->
            logger.error("{}: a coroutine of plugin {} failed", name, plugin, redactor.error(error))
        }
        val scope = Scope(plugin, runtime + SupervisorJob(runtime[Job]) + CoroutineName(plugin) + failed)
        scopes += scope
        return scope
    }

    /** A scope for the channel instance [instance] of [plugin], a child of the plugin's scope. */
    fun create(plugin: String, instance: ChannelInstanceId): CoroutineScope {
        val parent = scopes.first { it.plugin == plugin }.coroutineContext
        val failed = CoroutineExceptionHandler { _, error ->
            logger.error("{}: a coroutine of channel instance {} failed", name, instance, redactor.error(error))
        }
        return CoroutineScope(parent + SupervisorJob(parent.job) + CoroutineName("$plugin $instance") + failed)
    }

    /** Cancels [scope] of [instance] of [plugin], and reports it when its coroutines still run at [deadline]. */
    suspend fun cancel(
        scope: CoroutineScope,
        plugin: String,
        instance: ChannelInstanceId,
        deadline: TimeMark,
        grace: Duration,
    ): Problem? {
        val job = scope.coroutineContext.job
        job.cancel()
        withTimeoutOrNull(timeLeft(deadline)) { job.join() }
        if (job.isCompleted) return null
        abandoned += job
        return notDone("channel instance $instance (plugin $plugin)", plugin, grace)
    }

    /** Cancels every plugin scope and reports those whose coroutines still run at [deadline]. */
    suspend fun cancel(deadline: TimeMark, grace: Duration): List<Problem> {
        val jobs = scopes.map { it.plugin to it.coroutineContext.job }
        jobs.forEach { (_, job) -> job.cancel() }
        withTimeoutOrNull(timeLeft(deadline)) { jobs.flatMap { (_, job) -> running(job) }.forEach { it.join() } }
        return jobs.filter { (_, job) -> running(job).any() }
            .map { (plugin, _) -> notDone("plugin $plugin", plugin, grace) }
    }

    /** The children of [job] still running, the abandoned instance scopes aside. */
    private fun running(job: Job): Sequence<Job> = job.children.filter { it !in abandoned }

    private fun timeLeft(deadline: TimeMark): Duration = (-deadline.elapsedNow()).coerceAtLeast(SETTLE_TIME)

    private fun notDone(owner: String, plugin: String, grace: Duration): Problem {
        val message = "Coroutines of $owner were still running: the shutdown grace of $grace ran out."
        logger.warn("{}: {}", name, message)
        return Problem(RuntimeProblemKind.PLUGIN_SCOPE_NOT_DONE, message, plugin)
    }

    private class Scope(val plugin: String, override val coroutineContext: CoroutineContext) : PluginScope
}
