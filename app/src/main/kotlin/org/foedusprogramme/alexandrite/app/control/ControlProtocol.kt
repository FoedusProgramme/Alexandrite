package org.foedusprogramme.alexandrite.app.control

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant

/** The version of the control protocol this app speaks. */
internal const val CONTROL_PROTOCOL = 1

/** What a client asks the running app. */
@Serializable
internal sealed interface ControlRequest {
    @Serializable
    @SerialName("ping")
    data object Ping : ControlRequest

    @Serializable
    @SerialName("pairings")
    data object ListPairings : ControlRequest

    @Serializable
    @SerialName("pair")
    class Approve(val code: String) : ControlRequest

    @Serializable
    @SerialName("deny")
    class Deny(val code: String) : ControlRequest

    @Serializable
    @SerialName("unpair")
    class Unpair(val chat: String) : ControlRequest
}

/** What the running app answers. */
@Serializable
internal sealed interface ControlReply {
    @Serializable
    @SerialName("pong")
    class Pong(val version: String) : ControlReply

    @Serializable
    @SerialName("pairings")
    class Pairings(val requests: List<RequestView>, val paired: List<PairedView>) : ControlReply

    @Serializable
    @SerialName("approved")
    class Approved(val request: RequestView) : ControlReply

    @Serializable
    @SerialName("denied")
    class Denied(val request: RequestView) : ControlReply

    /** [request] could not be kept as paired and waits under a new code. */
    @Serializable
    @SerialName("not_kept")
    class NotKept(val request: RequestView, val reason: String) : ControlReply

    @Serializable
    @SerialName("unknown_code")
    data object UnknownCode : ControlReply

    @Serializable
    @SerialName("malformed_code")
    data object MalformedCode : ControlReply

    @Serializable
    @SerialName("unpaired")
    class Unpaired(val chat: String) : ControlReply

    @Serializable
    @SerialName("not_paired")
    class NotPaired(val chat: String) : ControlReply

    /** The config file declares the chat. */
    @Serializable
    @SerialName("declared")
    class Declared(val chat: String) : ControlReply

    @Serializable
    @SerialName("unpair_failed")
    class UnpairFailed(val chat: String, val reason: String) : ControlReply

    /** No channel takes pairing codes. */
    @Serializable
    @SerialName("no_desk")
    data object NoDesk : ControlReply

    /** A request the app could not answer. */
    @Serializable
    @SerialName("failure")
    class Failure(val message: String) : ControlReply
}

/** A chat's request to be paired. */
@Serializable
internal class RequestView(
    val chat: String,
    val kind: String,
    val title: String? = null,
    val requester: RequesterView? = null,
    val code: String,
    @Serializable(with = InstantText::class)
    val expiresAt: Instant,
)

@Serializable
internal class RequesterView(val user: String, val name: String, val username: String? = null)

/** A paired chat. */
@Serializable
internal class PairedView(val chat: String, val declared: Boolean = false, val approval: ApprovalView? = null)

@Serializable
internal class ApprovalView(
    val kind: String,
    val title: String? = null,
    val requester: RequesterView? = null,
    @Serializable(with = InstantText::class)
    val approvedAt: Instant,
    /** Null for the host. */
    val approvedBy: String? = null,
)

/** A line that breaks the control protocol. */
internal class ControlProtocolException(override val message: String, cause: Throwable? = null) :
    IOException(message, cause)

internal fun encodeRequest(request: ControlRequest): String =
    json.encodeToString(RequestLine.serializer(), RequestLine(CONTROL_PROTOCOL, request))

internal fun encodeReply(reply: ControlReply): String =
    json.encodeToString(ReplyLine.serializer(), ReplyLine(CONTROL_PROTOCOL, reply))

internal fun decodeRequest(line: String): ControlRequest = decode(line) {
    json.decodeFromJsonElement(RequestLine.serializer(), it).request
}

internal fun decodeReply(line: String): ControlReply = decode(line) {
    json.decodeFromJsonElement(ReplyLine.serializer(), it).reply
}

private fun <T> decode(line: String, read: (JsonObject) -> T): T {
    val tree = try {
        json.parseToJsonElement(line) as? JsonObject
    } catch (e: SerializationException) {
        null
    } ?: throw ControlProtocolException("The line is no JSON object.")
    val protocol = tree["protocol"] ?: throw ControlProtocolException("The line names no control protocol.")
    if ((protocol as? JsonPrimitive)?.intOrNull != CONTROL_PROTOCOL) {
        throw ControlProtocolException(
            "The line speaks control protocol $protocol, this Alexandrite speaks $CONTROL_PROTOCOL.",
        )
    }
    return try {
        read(tree)
    } catch (e: IllegalArgumentException) {
        throw ControlProtocolException("The line is malformed: ${e.message?.lineSequence()?.first()}", e)
    }
}

@Serializable
private class RequestLine(val protocol: Int, val request: ControlRequest)

@Serializable
private class ReplyLine(val protocol: Int, val reply: ControlReply)

private val json = Json { ignoreUnknownKeys = true }

private object InstantText : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.foedusprogramme.alexandrite.app.control.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant {
        val text = decoder.decodeString()
        return try {
            Instant.parse(text)
        } catch (e: DateTimeException) {
            throw SerializationException("'$text' is no instant.")
        }
    }
}
