package org.foedusprogramme.alexandrite.runtime.lifecycle

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.sdk.plugin.PluginScope
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark

private val logger: Logger = LoggerFactory.getLogger(AlexandriteRuntime::class.java)

/** How long cancelled coroutines get to finish even when the shutdown deadline has passed. */
private val SETTLE_TIME = 100.milliseconds

/** The [PluginScope]s of a runtime, children of its coroutine scope [runtime]. */
internal class PluginScopes(
    private val name: String,
    private val redactor: Redactor,
    private val runtime: CoroutineContext,
) {
    private val scopes = CopyOnWriteArrayList<Scope>()

    fun create(plugin: String): PluginScope {
        val failed = CoroutineExceptionHandler { _, error ->
            logger.error("{}: a coroutine of plugin {} failed", name, plugin, redactor.error(error))
        }
        val scope = Scope(plugin, runtime + SupervisorJob(runtime[Job]) + CoroutineName(plugin) + failed)
        scopes += scope
        return scope
    }

    /** Cancels every scope and reports those whose coroutines still run at [deadline]. */
    suspend fun cancel(deadline: TimeMark, grace: Duration): List<Problem> {
        val jobs = scopes.map { it.plugin to it.coroutineContext.job }
        jobs.forEach { (_, job) -> job.cancel() }
        val left = (-deadline.elapsedNow()).coerceAtLeast(SETTLE_TIME)
        withTimeoutOrNull(left) { jobs.forEach { (_, job) -> job.join() } }
        return jobs.filterNot { (_, job) -> job.isCompleted }.map { (plugin, _) ->
            val message = "Coroutines of plugin $plugin were still running: the shutdown grace of $grace ran out."
            logger.warn("{}: {}", name, message)
            Problem(RuntimeProblemKind.PLUGIN_SCOPE_NOT_DONE, message, plugin, null)
        }
    }

    private class Scope(val plugin: String, override val coroutineContext: CoroutineContext) : PluginScope
}
