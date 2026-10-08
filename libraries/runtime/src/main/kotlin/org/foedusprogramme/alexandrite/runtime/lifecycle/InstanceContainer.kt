package org.foedusprogramme.alexandrite.runtime.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import org.foedusprogramme.alexandrite.sdk.channel.Channel
import org.foedusprogramme.alexandrite.sdk.channel.InstanceState
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.di.container.Container

/** The container of the channel instance [id] of [plugin], with the instance's scope and channel. */
internal class InstanceContainer(
    val id: ChannelInstanceId,
    val plugin: String,
    val container: Container,
    val scope: CoroutineScope,
    val channel: Channel,
) {
    val label: String get() = "channel instance container '$id'"

    private val lock = Any()

    @Volatile
    var state: InstanceState = InstanceState.STARTING
        private set

    /** Completed once the instance has stopped. */
    val stopped: CompletableDeferred<Unit> = CompletableDeferred()

    fun opened() {
        synchronized(lock) { if (state == InstanceState.STARTING) state = InstanceState.OPEN }
    }

    /** Moves the instance to STOPPING when it is in one of [from], and returns whether it did. */
    fun claim(from: Set<InstanceState>): Boolean = synchronized(lock) {
        (state in from).also { if (it) state = InstanceState.STOPPING }
    }

    fun markStopped() {
        synchronized(lock) { state = InstanceState.STOPPED }
        stopped.complete(Unit)
    }
}
