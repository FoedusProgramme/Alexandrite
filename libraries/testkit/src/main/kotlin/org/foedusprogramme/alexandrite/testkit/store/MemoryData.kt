package org.foedusprogramme.alexandrite.testkit.store

import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.store.ConversationInfo
import org.foedusprogramme.alexandrite.sdk.store.ConversationKind
import org.foedusprogramme.alexandrite.sdk.store.MediaInfo
import org.foedusprogramme.alexandrite.sdk.store.TurnRecord
import org.foedusprogramme.alexandrite.sdk.transcript.EntryRecord
import org.foedusprogramme.alexandrite.sdk.transcript.MediaId
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/** What a memory store holds, changed only inside [locked]. */
internal class MemoryData(private val clock: Clock) {
    private val lock = Any()

    val conversations = LinkedHashMap<ConversationId, ConversationInfo>()
    val current = HashMap<Pair<AgentChatKey, ConversationKind>, ConversationId>()
    val turns = HashMap<TurnId, TurnRecord>()
    val entries = sortedMapOf<Long, StoredEntry>()
    val media = HashMap<MediaId, StoredBytes>()
    var lastEntry = 0L

    /** Runs [block] alone, with the time to the millisecond. */
    fun <T> locked(block: MemoryData.(now: Instant) -> T): T =
        synchronized(lock) { block(clock.instant().truncatedTo(ChronoUnit.MILLIS)) }
}

/** An entry as a memory store keeps it, in the persisted form. */
internal class StoredEntry(
    val record: EntryRecord,
    val body: String,
    val message: ChannelMessageRef?,
    val media: Set<MediaId>,
)

internal class StoredBytes(val info: MediaInfo, val bytes: ByteArray)

private val random = SecureRandom()

/** A random id of 22 URL-safe characters. */
internal fun newId(): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
