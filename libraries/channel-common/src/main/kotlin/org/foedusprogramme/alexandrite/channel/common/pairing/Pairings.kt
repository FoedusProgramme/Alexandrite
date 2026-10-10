package org.foedusprogramme.alexandrite.channel.common.pairing

import dev.drewhamilton.poko.Poko
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.foedusprogramme.alexandrite.internal.pairing.PairingCodes
import org.foedusprogramme.alexandrite.internal.pairing.Redemption
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** A [PairingDesk] over the channel instances of a channel plugin, offering an unpaired chat one code per lifetime. */
public open class Pairings(private val clock: Clock, public val codeLifetime: Duration = 10.minutes) : PairingDesk {
    private val codes = PairingCodes<Party, ChatAddress>(clock, codeLifetime, capacity = CAPACITY)
    private val lock = Any()
    private val instances = LinkedHashMap<ChannelInstanceId, InstancePairing>()
    private val requests = LinkedHashMap<ChatAddress, PairingRequest>()

    /** When each chat that was offered a code may get the next one. */
    private val offered = object : LinkedHashMap<ChatAddress, Instant>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChatAddress, Instant>) = size > OFFERED
    }
    private val deciding = Mutex()

    /** Opens the pairing of [instance], whose paired chats are [paired] and kept by [store]. */
    public fun open(
        instance: ChannelInstanceId,
        paired: Collection<ChatAddress>,
        store: PairedChatStore,
    ): InstancePairing = synchronized(lock) {
        require(instance !in instances) { "The pairing of $instance is open already." }
        require(paired.all { it.instance == instance }) { "The paired chats of $instance hold another instance's." }
        InstancePairing(instance, this, paired.mapTo(LinkedHashSet()) { it.parent }, store)
            .also { instances[instance] = it }
    }

    /** Closes the pairing of [instance] and drops its requests. */
    public fun close(instance: ChannelInstanceId) {
        synchronized(lock) {
            instances.remove(instance) ?: return
            requests.keys.filter { it.instance == instance }.forEach(::drop)
            offered.keys.removeIf { it.instance == instance }
        }
    }

    override fun pending(): List<PairingRequest> = synchronized(lock) {
        purge(clock.instant())
        requests.values.sortedBy { it.issuedAt }
    }

    override suspend fun approve(code: String): PairingDecision = decide(code) { request, pairing ->
        try {
            pairing.store.add(request.chat)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val waiting = reissue(request)
            logger.warn("Cannot pair chat {}, whose request now holds a new code: {}", request.chat, e.toString())
            return@decide PairingDecision.Failed(waiting, e.message ?: e.toString())
        }
        synchronized(lock) { pairing.paired += request.chat }
        logger.info("Chat {} is paired", request.chat)
        PairingDecision.Approved(request)
    }

    override suspend fun deny(code: String): PairingDecision = decide(code) { request, _ ->
        logger.info("The request of chat {} to be paired is denied", request.chat)
        PairingDecision.Denied(request)
    }

    override fun paired(): List<ChatAddress> = synchronized(lock) { instances.values.flatMap { it.paired.toList() } }

    override suspend fun unpair(chat: ChatAddress): Unpairing = deciding.withLock {
        val key = chat.parent
        val pairing = synchronized(lock) { instances[key.instance]?.takeIf { key in it.paired } }
            ?: return@withLock Unpairing.NotPaired
        val removed = try {
            pairing.store.remove(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Cannot unpair chat {}: {}", key, e.toString())
            return@withLock Unpairing.Failed(e.message ?: e.toString())
        }
        if (!removed) return@withLock Unpairing.ByReference
        synchronized(lock) { pairing.paired -= key }
        logger.info("Chat {} is unpaired", key)
        Unpairing.Unpaired
    }

    internal fun isPaired(pairing: InstancePairing, chat: ChatAddress): Boolean =
        synchronized(lock) { chat.parent in pairing.paired }

    internal fun request(
        pairing: InstancePairing,
        chat: ChatAddress,
        kind: ChatKind,
        title: String?,
        requester: ChatUser?,
    ): PairingCode? = synchronized(lock) {
        val key = chat.parent
        if (key in pairing.paired || instances[pairing.instance] !== pairing) return null
        val now = clock.instant()
        purge(now)
        if (key in offered) return null
        val issued = codes.issue(PURPOSE, Party.Chat(key), key)
        offered[key] = issued.expiresAt
        requests.remove(key)
        requests[key] = PairingRequest(key, kind, title, requester, issued.text, now, issued.expiresAt)
        while (requests.size > CAPACITY) drop(requests.keys.first())
        PairingCode(issued.text, issued.expiresAt)
    }

    /** What [onRequest] makes of the request that holds [code], one decision at a time. */
    private suspend fun decide(
        code: String,
        onRequest: suspend (PairingRequest, InstancePairing) -> PairingDecision,
    ): PairingDecision = deciding.withLock {
        val found = synchronized(lock) {
            purge(clock.instant())
            when (val redemption = codes.redeem(PURPOSE, code, Party.Operator)) {
                is Redemption.Redeemed -> {
                    val chat = redemption.payload
                    checkNotNull(requests.remove(chat)) to checkNotNull(instances[chat.instance])
                }

                is Redemption.Rejected -> return@withLock PairingDecision.Unknown(redemption.triesLeft)

                is Redemption.Locked -> return@withLock PairingDecision.Locked(redemption.until)

                Redemption.Malformed, Redemption.OwnCode -> return@withLock PairingDecision.Malformed
            }
        }
        onRequest(found.first, found.second)
    }

    /** [request] under a new code, waiting again. */
    private fun reissue(request: PairingRequest): PairingRequest = synchronized(lock) {
        val issued = codes.issue(PURPOSE, Party.Chat(request.chat), request.chat)
        val waiting = PairingRequest(
            request.chat,
            request.kind,
            request.title,
            request.requester,
            issued.text,
            clock.instant(),
            issued.expiresAt,
        )
        requests[request.chat] = waiting
        waiting
    }

    private fun purge(now: Instant) {
        requests.values.removeIf { !now.isBefore(it.expiresAt) }
        offered.values.removeIf { !now.isBefore(it) }
    }

    private fun drop(chat: ChatAddress) {
        requests.remove(chat)
        codes.revoke(PURPOSE, Party.Chat(chat))
    }

    /** Who issues or redeems a code. */
    private sealed interface Party {
        data class Chat(val address: ChatAddress) : Party

        data object Operator : Party
    }

    private companion object {
        const val PURPOSE = "pair"

        /** The requests kept, dropping the oldest. */
        const val CAPACITY = 1_000

        /** The chats whose last offer is kept, dropping the least recent. */
        const val OFFERED = 4096

        val logger = LoggerFactory.getLogger(Pairings::class.java)
    }
}

/** The pairing of one channel instance's chats. */
public class InstancePairing internal constructor(
    public val instance: ChannelInstanceId,
    private val desk: Pairings,
    internal val paired: MutableSet<ChatAddress>,
    internal val store: PairedChatStore,
) {
    /** Whether [chat], or the chat whose thread it is, is paired. */
    public fun isPaired(chat: ChatAddress): Boolean = desk.isPaired(this, chat)

    /** A code for the unpaired [chat], null when it is paired or was offered one within a code's lifetime. */
    public fun request(chat: ChatAddress, kind: ChatKind, title: String?, requester: ChatUser?): PairingCode? =
        desk.request(this, chat, kind, title, requester)
}

/** A code offered to an unpaired chat. */
@Poko
public class PairingCode(public val text: String, public val expiresAt: Instant)
