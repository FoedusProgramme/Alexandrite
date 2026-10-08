package org.foedusprogramme.alexandrite.sdk.model

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/** A model call that failed. */
public class ModelException(public val error: ModelError, cause: Throwable? = null) :
    Exception("${error.kind}: ${error.message}", cause)

/** Why a model call failed. */
@Poko
public class ModelError private constructor(
    public val kind: ModelErrorKind,
    public val message: String,
    /** Whether sending the same request again may succeed, which a builder assumes for the transient kinds. */
    public val retryable: Boolean,
    /** How long the backend asked to wait, null when it did not say. */
    public val retryAfter: Duration?,
    /** The HTTP status, null when there is none. */
    public val status: Int?,
    /** The backend's id of the request. */
    public val requestId: String?,
    /** The backend's own error type. */
    public val rawType: String?,
    /** Whether the call emitted an event before it failed. */
    public val outputStarted: Boolean,
) {
    init {
        require(message.isNotBlank()) { "A model error's message may not be blank." }
        require(retryAfter == null || (retryAfter.isFinite() && !retryAfter.isNegative())) {
            "A retry delay is finite and at least 0, was $retryAfter."
        }
        require(status == null || status in 100..599) { "An HTTP status lies between 100 and 599, was $status." }
    }

    public fun toBuilder(): Builder = Builder(kind, message)
        .retryable(retryable)
        .retryAfter(retryAfter)
        .status(status)
        .requestId(requestId)
        .rawType(rawType)
        .outputStarted(outputStarted)

    public class Builder internal constructor(private var kind: ModelErrorKind, private var message: String) {
        private var retryable: Boolean? = null
        private var retryAfter: Duration? = null
        private var status: Int? = null
        private var requestId: String? = null
        private var rawType: String? = null
        private var outputStarted: Boolean = false

        public fun kind(kind: ModelErrorKind): Builder = apply { this.kind = kind }

        public fun message(message: String): Builder = apply { this.message = message }

        public fun retryable(retryable: Boolean): Builder = apply { this.retryable = retryable }

        public fun retryAfter(retryAfter: Duration?): Builder = apply { this.retryAfter = retryAfter }

        public fun status(status: Int?): Builder = apply { this.status = status }

        public fun requestId(requestId: String?): Builder = apply { this.requestId = requestId }

        public fun rawType(rawType: String?): Builder = apply { this.rawType = rawType }

        public fun outputStarted(outputStarted: Boolean): Builder = apply { this.outputStarted = outputStarted }

        public fun build(): ModelError = ModelError(
            kind,
            message,
            retryable ?: (kind in TRANSIENT),
            retryAfter,
            status,
            requestId,
            rawType,
            outputStarted,
        )
    }

    public companion object {
        public fun builder(kind: ModelErrorKind, message: String): Builder = Builder(kind, message)
    }
}

public inline fun ModelError.rebuild(block: ModelError.Builder.() -> Unit): ModelError =
    toBuilder().apply(block).build()

private val TRANSIENT = setOf(
    ModelErrorKind.RATE_LIMITED,
    ModelErrorKind.OVERLOADED,
    ModelErrorKind.SERVER_ERROR,
    ModelErrorKind.TIMEOUT,
    ModelErrorKind.CONNECTION,
)

/** What kind of failure a [ModelError] is. */
@JvmInline
@Serializable
public value class ModelErrorKind internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** Credentials missing or refused. */
        public val AUTHENTICATION: ModelErrorKind = ModelErrorKind("authentication")

        public val PERMISSION_DENIED: ModelErrorKind = ModelErrorKind("permission_denied")

        /** Billing or spending limits reached. */
        public val QUOTA_EXHAUSTED: ModelErrorKind = ModelErrorKind("quota_exhausted")

        public val RATE_LIMITED: ModelErrorKind = ModelErrorKind("rate_limited")

        /** The backend is too busy to answer. */
        public val OVERLOADED: ModelErrorKind = ModelErrorKind("overloaded")

        public val SERVER_ERROR: ModelErrorKind = ModelErrorKind("server_error")

        public val TIMEOUT: ModelErrorKind = ModelErrorKind("timeout")

        /** The backend could not be reached. */
        public val CONNECTION: ModelErrorKind = ModelErrorKind("connection")

        /** The backend refused the request as malformed. */
        public val INVALID_REQUEST: ModelErrorKind = ModelErrorKind("invalid_request")

        /** The prompt does not fit the model's context window. */
        public val CONTEXT_WINDOW_EXCEEDED: ModelErrorKind = ModelErrorKind("context_window_exceeded")

        public val MODEL_NOT_FOUND: ModelErrorKind = ModelErrorKind("model_not_found")

        /** A content filter blocked the request or the response. */
        public val CONTENT_FILTERED: ModelErrorKind = ModelErrorKind("content_filtered")

        /** The request asks for something the model cannot do. */
        public val UNSUPPORTED: ModelErrorKind = ModelErrorKind("unsupported")

        /** A response that breaks its protocol or the stream contract. */
        public val PROTOCOL: ModelErrorKind = ModelErrorKind("protocol")

        /** The values this version knows. */
        public val entries: List<ModelErrorKind> = listOf(
            AUTHENTICATION,
            PERMISSION_DENIED,
            QUOTA_EXHAUSTED,
            RATE_LIMITED,
            OVERLOADED,
            SERVER_ERROR,
            TIMEOUT,
            CONNECTION,
            INVALID_REQUEST,
            CONTEXT_WINDOW_EXCEEDED,
            MODEL_NOT_FOUND,
            CONTENT_FILTERED,
            UNSUPPORTED,
            PROTOCOL,
        )

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): ModelErrorKind = ModelErrorKind(id)
    }
}
