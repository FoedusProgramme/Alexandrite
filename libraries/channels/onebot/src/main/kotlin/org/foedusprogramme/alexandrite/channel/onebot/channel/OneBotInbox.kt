package org.foedusprogramme.alexandrite.channel.onebot.channel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.foedusprogramme.alexandrite.channel.onebot.config.OneBotInstanceConfig
import org.foedusprogramme.alexandrite.channel.onebot.mapping.OneBotMessages
import org.foedusprogramme.alexandrite.channel.onebot.protocol.event.OneBotEvent
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.turn.Submission
import org.foedusprogramme.alexandrite.sdk.turn.TurnSubmitter
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * What the implementation reports, taken into the agent.
 *
 * A message becomes a submission, which the agent queues as a turn of its chat. Every other event, a notice, a request
 * or a heartbeat, is read and dropped here: it is not a turn, and this version has no way to hand one to another
 * plugin. The connection can carry it, so the way out is a decision about the shape of that interface rather than
 * about this class, and until it is taken a plugin that needs a notice or a request goes through [OneBotApi] for the
 * state it wants. An instance whose runtime has no agent accepts the events and hands them nowhere, which is reported
 * once so that the silence is visible rather than puzzling.
 */
@ChannelInstanceScoped
internal class OneBotInbox(
    private val instance: ChannelInstance,
    private val config: OneBotInstanceConfig,
    private val link: OneBotLink,
    /** The agent, null while the runtime has none. */
    private val submitter: TurnSubmitter?,
) : Lifecycle {
    private var reading: Job? = null

    override suspend fun onStart() {
        if (submitter == null) {
            logger.warn(
                "{}: the runtime has no agent, so the messages of channel instance {} reach nobody",
                PLUGIN,
                instance.id,
            )
            return
        }
        reading = instance.scope.launch { read() }
    }

    override suspend fun onClose() {
        reading?.cancel()
        reading = null
    }

    private suspend fun read() {
        link.events.collect { event ->
            if (event !is OneBotEvent.Message) return@collect
            val message = OneBotMessages.incoming(instance.id, event, config.admins)
            submitter?.submit(Submission.Message(message))
        }
    }

    private companion object {
        const val PLUGIN = "alexandrite-channel-onebot"

        val logger: Logger = LoggerFactory.getLogger(OneBotInbox::class.java)
    }
}
