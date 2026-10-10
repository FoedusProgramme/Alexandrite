package org.foedusprogramme.alexandrite.agent.turn

import org.foedusprogramme.alexandrite.sdk.channel.IncomingMessage
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.ReplyTarget
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.model.Trust
import org.foedusprogramme.alexandrite.sdk.turn.ReplyRoute
import java.time.Instant

/** A turn as its worker queues it. */
internal class TurnPlan(
    val id: TurnId,
    /** The worker's key, at the anchor of a linked group. */
    val key: AgentChatKey,
    /** The chat the turn came from, which its replies and policy follow. */
    val origin: ChatAddress,
    val kind: TurnKind,
    val input: TurnSource,
    val actor: ChatUser?,
    val replyTarget: ReplyTarget,
    val trigger: ChannelMessageRef?,
    val queuedAt: Instant,
    /** Where the final reply of a turn that replies to its caller goes. */
    val route: ReplyRoute = ReplyRoute.None,
)

/** What a turn answers. */
internal sealed interface TurnSource {
    class FromMessage(val message: IncomingMessage) : TurnSource

    /** The [prompt] that the plugin [plugin] started the turn with. */
    class FromInitiated(val plugin: String, val prompt: String, val trust: Trust) : TurnSource
}
