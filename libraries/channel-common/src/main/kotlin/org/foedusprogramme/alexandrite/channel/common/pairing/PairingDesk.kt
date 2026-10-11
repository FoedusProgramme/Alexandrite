package org.foedusprogramme.alexandrite.channel.common.pairing

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.di.ContributedSpi
import java.time.Instant

/**
 * The pairing of one channel plugin's chats, which an app approves and revokes for the operator.
 *
 * New members come with default bodies only.
 */
@ContributedSpi
public interface PairingDesk {
    /** The requests of unpaired chats whose codes are still valid, oldest first. */
    public fun pending(): List<PairingRequest>

    /** Whether a request of this desk holds [code]. */
    public fun holds(code: String): Boolean

    /** Pairs the chat that got [code] at once. */
    public suspend fun approve(code: String): PairingDecision

    /** Drops the request of the chat that got [code]. */
    public suspend fun deny(code: String): PairingDecision

    public fun paired(): List<PairedChat>

    public suspend fun unpair(chat: ChatAddress): Unpairing
}

/** A paired chat. */
@Poko
public class PairedChat(
    public val chat: ChatAddress,
    /** Whether the config file declares the chat. */
    public val declared: Boolean,
    /** How the chat's request was approved, null when it was not. */
    public val approval: PairingApproval?,
)

/** An approved request to be paired. */
@Poko
public class PairingApproval(
    public val kind: ChatKind,
    public val title: String?,
    /** Who sent the message that got the code, null when the platform did not say. */
    public val requester: ChatUser?,
    public val approvedAt: Instant,
    /** Who approved the code, null for the host. */
    public val approvedBy: UserAddress?,
)

/** An unpaired chat's request to be paired. */
@Poko
public class PairingRequest(
    public val chat: ChatAddress,
    public val kind: ChatKind,
    public val title: String?,
    /** Who sent the message, null when the platform did not say. */
    public val requester: ChatUser?,
    /** The code the chat was offered. */
    public val code: String,
    public val issuedAt: Instant,
    public val expiresAt: Instant,
)

/** What a [PairingDesk] made of a code. */
public sealed interface PairingDecision {
    @Poko
    public class Approved(public val request: PairingRequest) : PairingDecision

    @Poko
    public class Denied(public val request: PairingRequest) : PairingDecision

    /** The chat could not be kept as paired, and its request now holds a new code. */
    @Poko
    public class Failed(public val request: PairingRequest, public val reason: String) : PairingDecision

    /** No request holds the code. */
    public data object Unknown : PairingDecision

    /** Input that holds no code. */
    public data object Malformed : PairingDecision
}

/** What [PairingDesk.unpair] did. */
public sealed interface Unpairing {
    public data object Unpaired : Unpairing

    public data object NotPaired : Unpairing

    /** The config file declares the chat. */
    public data object Declared : Unpairing

    @Poko
    public class Failed(public val reason: String) : Unpairing
}
