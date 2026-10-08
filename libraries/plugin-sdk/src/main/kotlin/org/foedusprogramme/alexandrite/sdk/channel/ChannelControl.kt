package org.foedusprogramme.alexandrite.sdk.channel

import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.runtime.HostApi

/** Stops channel instances while the runtime runs on. */
@HostApi
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ChannelControl {
    /** The state of [instance], null when it is not configured. */
    public fun state(instance: ChannelInstanceId): InstanceState?

    /** Closes, drains, stops and destroys [instance] alone, on behalf of [by], and returns whether it was open. */
    public suspend fun stop(instance: ChannelInstanceId, by: ChatUser?): Boolean
}

/** Where a channel instance is in its life. */
@JvmInline
@Serializable
public value class InstanceState internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** Created but not open yet. */
        public val STARTING: InstanceState = InstanceState("starting")

        public val OPEN: InstanceState = InstanceState("open")

        public val STOPPING: InstanceState = InstanceState("stopping")

        public val STOPPED: InstanceState = InstanceState("stopped")
    }
}
