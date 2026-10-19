package org.unitedsoftware.alexandrite.channel.onebot.channel

import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.hook.Hook
import org.unitedsoftware.alexandrite.sdk.hook.HookDecision
import org.unitedsoftware.alexandrite.sdk.hook.InterceptorHook
import org.unitedsoftware.alexandrite.sdk.hook.InterceptorPoint
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.turn.ModelReply
import org.unitedsoftware.alexandrite.sdk.turn.TurnPoints
import java.util.concurrent.ConcurrentHashMap

/** The reply of a turn, as the words of a model's request reach it. */
internal interface OneBotWords {
    /** Shows [message], the response of one request of this turn. */
    suspend fun showResponse(message: AssistantEntry)
}

/**
 * The replies of the turns of this instance that are open, by turn.
 *
 * A reply opens when the agent dequeues a turn and closes as the turn ends, so the words of a response reach the reply
 * of its own turn and no other. A turn whose reply is not here is one this instance does not speak for, such as a
 * delegated turn whose reply goes to the agent that asked for it.
 */
@Singleton
internal class OneBotReplyRegistry {
    private val open = ConcurrentHashMap<TurnId, OneBotWords>()

    /** Remembers [words] as the reply of [turn]. */
    fun open(turn: TurnId, words: OneBotWords) {
        open[turn] = words
    }

    /** Forgets the reply of [turn], which nothing speaks for afterwards. */
    fun close(turn: TurnId) {
        open.remove(turn)
    }

    /** The reply of [turn], null when this instance speaks for no such turn. */
    fun of(turn: TurnId): OneBotWords? = open[turn]
}

/**
 * Tells the reply of a turn the words of each of its requests, whole and once, as the request ends.
 *
 * This is what the platform needs where a message cannot be added to later: a channel that can change its messages
 * shows a growing preview and has no use for this point, while a chat of this one is told each response in full at the
 * moment the model finished saying it.
 */
@Singleton
@Contribute(Hook::class)
internal class OneBotWordsHook(private val replies: OneBotReplyRegistry) : InterceptorHook<ModelReply> {
    override val point: InterceptorPoint<ModelReply> get() = TurnPoints.LLM_RESPONSE

    override suspend fun intercept(payload: ModelReply): HookDecision<ModelReply> {
        // A turn this instance speaks for has a reply here; a delegated turn has none, and its words go to its caller.
        val words = replies.of(payload.turn.id) ?: return HookDecision.Continue
        // A response that calls no tool is the answer of the turn, which the message that ends it carries: telling it
        // here as well would say it twice.
        if (payload.response.message.parts.none { it is ToolCallPart }) return HookDecision.Continue
        words.showResponse(payload.response.message)
        return HookDecision.Continue
    }
}
