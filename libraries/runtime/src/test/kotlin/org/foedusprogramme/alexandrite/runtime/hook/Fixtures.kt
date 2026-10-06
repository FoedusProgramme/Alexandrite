package org.foedusprogramme.alexandrite.runtime.hook

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.foedusprogramme.alexandrite.sdk.hook.Delivery
import org.foedusprogramme.alexandrite.sdk.hook.FailurePolicy
import org.foedusprogramme.alexandrite.sdk.hook.Hook
import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
import org.foedusprogramme.alexandrite.sdk.hook.HookEffect
import org.foedusprogramme.alexandrite.sdk.hook.HookFailure
import org.foedusprogramme.alexandrite.sdk.hook.HookPoint
import org.foedusprogramme.alexandrite.sdk.hook.Interception
import org.foedusprogramme.alexandrite.sdk.hook.InterceptorHook
import org.foedusprogramme.alexandrite.sdk.hook.InterceptorPoint
import org.foedusprogramme.alexandrite.sdk.hook.ObserverHook
import org.foedusprogramme.alexandrite.sdk.hook.ObserverPoint
import java.util.Collections
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun rewritePoint(onFailure: FailurePolicy = FailurePolicy.FAIL_OPEN): InterceptorPoint<String> =
    InterceptorPoint("test.rewrite", setOf(HookEffect.REPLACE, HookEffect.ABORT), onFailure)

fun guardPoint(): InterceptorPoint<String> =
    InterceptorPoint("test.guard", setOf(HookEffect.ABORT), FailurePolicy.FAIL_CLOSED)

fun seenPoint(): ObserverPoint<String> = ObserverPoint("test.seen")

open class TestInterceptor<P : Any>(
    override val point: InterceptorPoint<P>,
    override val order: Int = 0,
    override val timeout: Duration = 10.seconds,
    private val block: suspend (P) -> HookDecision<P>,
) : InterceptorHook<P> {
    override suspend fun intercept(payload: P): HookDecision<P> = block(payload)
}

open class TestObserver<P : Any>(
    override val point: ObserverPoint<P>,
    override val order: Int = 0,
    override val timeout: Duration = 10.seconds,
    override val delivery: Delivery = Delivery.INLINE,
    private val block: suspend (P) -> Unit,
) : ObserverHook<P> {
    override suspend fun observe(payload: P) = block(payload)
}

data class Reported(val hook: String, val point: HookPoint<*>, val failure: HookFailure)

internal class RecordingListener : HookFailureListener {
    private val reports = Collections.synchronizedList(mutableListOf<Reported>())

    override fun onFailure(hook: Hook, point: HookPoint<*>, failure: HookFailure) {
        reports += Reported(hook::class.java.name, point, failure)
    }

    fun all(): List<Reported> = synchronized(reports) { reports.toList() }
}

class Records {
    private val records = Collections.synchronizedList(mutableListOf<String>())

    fun add(record: String) {
        records += record
    }

    fun all(): List<String> = synchronized(records) { records.toList() }
}

internal fun TestScope.dispatcher(listener: HookFailureListener, vararg hooks: Hook, asyncCapacity: Int = 256) =
    HookDispatcher(hooks.toList(), listener, asyncCapacity, StandardTestDispatcher(testScheduler))
