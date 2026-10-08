package org.foedusprogramme.alexandrite.sdk.channel

import kotlinx.coroutines.CoroutineScope
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId

/** The channel instance whose container holds a channel-instance-scoped component. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ChannelInstance {
    public val id: ChannelInstanceId

    /** The scope of the instance's long-running coroutines, a child of its plugin's scope cancelled after its stop. */
    public val scope: CoroutineScope
}

/** The channels of the configured channel instances. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ChannelDirectory {
    /** Every channel instance in the config. */
    public val instances: Set<ChannelInstanceId>

    /** The channel of [instance] from the end of its open until its stop, null outside that time. */
    public fun channel(instance: ChannelInstanceId): Channel?
}
