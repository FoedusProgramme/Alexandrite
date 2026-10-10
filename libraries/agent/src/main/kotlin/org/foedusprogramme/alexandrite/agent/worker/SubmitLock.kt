package org.foedusprogramme.alexandrite.agent.worker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.plugin.PluginScope
import java.util.concurrent.locks.ReentrantLock

/** The lock over the intakes and the workers, held only by code that does not suspend. */
@Singleton
internal class SubmitLock(private val scope: PluginScope) {
    private val lock = ReentrantLock()
    private val starts = ArrayList<Job>()

    fun <T> locked(block: () -> T): T {
        var ready = emptyList<Job>()
        lock.lock()
        try {
            return block()
        } finally {
            if (lock.holdCount == 1) {
                ready = starts.toList()
                starts.clear()
            }
            lock.unlock()
            ready.forEach(Job::start)
        }
    }

    /** Launches [block] in the plugin's scope once the lock is released, which the caller holds. */
    fun launch(block: suspend CoroutineScope.() -> Unit): Job {
        check(lock.isHeldByCurrentThread) { "Coroutines are launched under the submit lock." }
        return scope.launch(start = CoroutineStart.LAZY, block = block).also { starts += it }
    }
}
