package org.foedusprogramme.alexandrite.runtime.channel

import kotlinx.coroutines.CoroutineScope
import org.foedusprogramme.alexandrite.sdk.channel.Channel
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import java.util.concurrent.ConcurrentHashMap

/** The [ChannelDirectory] of a runtime, holding the channels that [join] adds and [leave] removes. */
internal class InstanceDirectory(override val instances: Set<ChannelInstanceId>) : ChannelDirectory {
    private val open = ConcurrentHashMap<ChannelInstanceId, Channel>()

    override fun channel(instance: ChannelInstanceId): Channel? = open[instance]

    fun join(instance: ChannelInstanceId, channel: Channel) {
        open[instance] = channel
    }

    fun leave(instance: ChannelInstanceId) {
        open.remove(instance)
    }
}

internal class RuntimeChannelInstance(override val id: ChannelInstanceId, override val scope: CoroutineScope) :
    ChannelInstance
