package org.foedusprogramme.alexandrite.runtime.lifecycle

import kotlinx.coroutines.CoroutineScope
import org.foedusprogramme.alexandrite.sdk.channel.Channel
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
}
