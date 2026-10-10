package org.foedusprogramme.alexandrite.agent.i18n

import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind

/** The keys of the agent's texts for people. */
internal object TextKeys {
    const val HOOK_ABORTED: String = "notice.hook_aborted"
    const val BLANK_REPLY: String = "notice.blank_reply"
    const val REFUSED: String = "notice.refused"
    const val CONTEXT_WINDOW: String = "notice.context_window"
    const val OUTPUT_LIMIT: String = "notice.output_limit"
    const val OUTPUT_LIMIT_UNANSWERED: String = "notice.output_limit_unanswered"
    const val REPLY_TOO_LONG: String = "notice.reply_too_long"
    const val FAILED: String = "notice.failed"

    /** Takes `{command}`. */
    const val UNKNOWN_COMMAND: String = "notice.unknown_command"

    /** Takes `{model}`. */
    const val MODEL_UNKNOWN: String = "notice.model.unknown"
    const val MODEL_AUTHENTICATION: String = "notice.model.authentication"
    const val MODEL_PERMISSION_DENIED: String = "notice.model.permission_denied"
    const val MODEL_QUOTA_EXHAUSTED: String = "notice.model.quota_exhausted"
    const val MODEL_UNAVAILABLE: String = "notice.model.unavailable"
    const val MODEL_CONTENT_FILTERED: String = "notice.model.content_filtered"
    const val MODEL_UNSUPPORTED: String = "notice.model.unsupported"

    /** Takes `{kind}`. */
    const val MODEL_FAILED: String = "notice.model.failed"

    /** The key of the notice for a model call that failed with [kind]. */
    fun modelFailure(kind: ModelErrorKind): String = when (kind) {
        ModelErrorKind.AUTHENTICATION -> MODEL_AUTHENTICATION

        ModelErrorKind.PERMISSION_DENIED -> MODEL_PERMISSION_DENIED

        ModelErrorKind.QUOTA_EXHAUSTED -> MODEL_QUOTA_EXHAUSTED

        ModelErrorKind.CONTEXT_WINDOW_EXCEEDED -> CONTEXT_WINDOW

        ModelErrorKind.MODEL_NOT_FOUND -> MODEL_UNKNOWN

        ModelErrorKind.CONTENT_FILTERED -> MODEL_CONTENT_FILTERED

        ModelErrorKind.UNSUPPORTED -> MODEL_UNSUPPORTED

        ModelErrorKind.RATE_LIMITED,
        ModelErrorKind.OVERLOADED,
        ModelErrorKind.SERVER_ERROR,
        ModelErrorKind.TIMEOUT,
        ModelErrorKind.CONNECTION,
        -> MODEL_UNAVAILABLE

        else -> MODEL_FAILED
    }
}
