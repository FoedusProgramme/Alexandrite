package org.foedusprogramme.alexandrite.agent.worker

import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom

/** A new turn id, `t-` and a ULID of [now]. */
internal fun newTurnId(now: Instant): TurnId {
    val chars = CharArray(ULID_LENGTH)
    var time = now.toEpochMilli()
    for (index in TIME_LENGTH - 1 downTo 0) {
        chars[index] = CROCKFORD[(time and 31).toInt()]
        time = time ushr 5
    }
    val random = ThreadLocalRandom.current()
    for (index in TIME_LENGTH until ULID_LENGTH) chars[index] = CROCKFORD[random.nextInt(CROCKFORD.length)]
    return TurnId("t-${String(chars)}")
}

private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
private const val TIME_LENGTH = 10
private const val ULID_LENGTH = 26
