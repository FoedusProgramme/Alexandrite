package org.foedusprogramme.alexandrite.agent.turn

import org.foedusprogramme.alexandrite.sdk.hook.Hook
import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
import org.foedusprogramme.alexandrite.sdk.hook.InterceptorHook
import org.foedusprogramme.alexandrite.sdk.hook.InterceptorPoint
import org.foedusprogramme.alexandrite.sdk.hook.ObserverHook
import org.foedusprogramme.alexandrite.sdk.hook.ObserverPoint
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import java.util.concurrent.CopyOnWriteArrayList

internal fun <P : Any> stopper(point: InterceptorPoint<P>, reply: String?): Hook =
    Interceptor(point, CopyOnWriteArrayList()) { HookDecision.Abort(reply) }

/** An interceptor that records the id of its point and decides with [decide]. */
internal class Interceptor<P : Any>(
    override val point: InterceptorPoint<P>,
    private val fired: MutableList<String> = CopyOnWriteArrayList(),
    private val decide: (P) -> HookDecision<P> = { HookDecision.Continue },
) : InterceptorHook<P> {
    val seen: MutableList<P> = CopyOnWriteArrayList()

    override suspend fun intercept(payload: P): HookDecision<P> {
        fired += point.id
        seen += payload
        return decide(payload)
    }
}

/** An observer that keeps what it saw and records the id of its point. */
internal class Observer<P : Any>(
    override val point: ObserverPoint<P>,
    private val fired: MutableList<String> = CopyOnWriteArrayList(),
) : ObserverHook<P> {
    val seen: MutableList<P> = CopyOnWriteArrayList()

    override suspend fun observe(payload: P) {
        fired += point.id
        seen += payload
    }
}

internal fun TranscriptEntry.withoutRecord(): TranscriptEntry = when (this) {
    is UserEntry -> UserEntry(null, parts, origin)
    is AssistantEntry -> AssistantEntry(null, parts, producedBy, providerData)
    is NoticeEntry -> NoticeEntry(null, text, kind)
    else -> this
}
