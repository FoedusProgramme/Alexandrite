package org.foedusprogramme.alexandrite.channel.qqbot.channel

import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.hook.Hook
import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
import org.foedusprogramme.alexandrite.sdk.hook.InterceptorHook
import org.foedusprogramme.alexandrite.sdk.hook.InterceptorPoint
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.turn.ModelReply
import org.foedusprogramme.alexandrite.sdk.turn.TurnPoints
import java.util.concurrent.ConcurrentHashMap

/** The reply of a turn, as the words of a model's request reach it. */
internal interface QqBotWords {
    /** Shows [message], the response of one request of this turn. */
    suspend fun showResponse(message: AssistantEntry)
}

/** The replies of the turns of this instance that are open, by turn. */
@Singleton
internal class QqBotReplyRegistry {
    private val open = ConcurrentHashMap<TurnId, QqBotWords>()

    /** Remembers [words] as the reply of [turn]. */
    fun open(turn: TurnId, words: QqBotWords) {
        open[turn] = words
    }

    /** Forgets the reply of [turn]. */
    fun close(turn: TurnId) {
        open.remove(turn)
    }

    /** The reply of [turn], null when this instance speaks for no such turn. */
    fun of(turn: TurnId): QqBotWords? = open[turn]
}

/**
 * Tells the reply of a turn the words of each of its requests, as OneBot does for a chat whose messages cannot be
 * rewritten. A streaming single chat already shows those words in its preview, so this only sends the responses of a
 * non-streaming chat.
 */
@Singleton
@Contribute(Hook::class)
internal class QqBotWordsHook(private val replies: QqBotReplyRegistry) : InterceptorHook<ModelReply> {
    override val point: InterceptorPoint<ModelReply> get() = TurnPoints.LLM_RESPONSE

    override suspend fun intercept(payload: ModelReply): HookDecision<ModelReply> {
        val words = replies.of(payload.turn.id) ?: return HookDecision.Continue
        // A response that calls no tool is the answer of the turn, which the message that ends it carries.
        if (payload.response.message.parts.none { it is ToolCallPart }) return HookDecision.Continue
        words.showResponse(payload.response.message)
        return HookDecision.Continue
    }
}
