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
    const val ROUND_LIMIT: String = "notice.round_limit"
    const val FAILED: String = "notice.failed"
    const val INTERRUPTED: String = "notice.interrupted"

    /** Takes `{agent}`, `{folder}` and `{files}`. */
    const val UNTRUSTED_FOLDER: String = "notice.untrusted_folder"

    /** Takes `{command}`. */
    const val UNKNOWN_COMMAND: String = "notice.unknown_command"

    /** Takes `{command}`. */
    const val COMMAND_FAILED: String = "notice.command_failed"

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

    /** Takes `{chat}`. */
    const val NO_AGENT: String = "control.no_agent"

    /** Takes `{agent}` and `{instance}`. */
    const val AGENT_NOT_ATTACHED: String = "control.agent_not_attached"

    /** Takes `{endpoint}`. */
    const val UNKNOWN_ENDPOINT: String = "control.unknown_endpoint"

    /** Takes `{endpoint}` and `{model}`. */
    const val UNKNOWN_MODEL: String = "control.unknown_model"

    /** Takes `{effort}` and `{efforts}`. */
    const val UNKNOWN_REASONING: String = "control.unknown_reasoning"

    /** Takes `{reason}`. */
    const val CONFIG_REFUSED: String = "control.config_refused"

    /** Takes `{chat}` and `{instance}`. */
    const val NOT_A_CHAT_OF: String = "control.not_a_chat_of"

    /** Takes `{chat}` and `{agent}`. */
    const val HOME_TAKEN: String = "control.home_taken"
    const val SELF_LINK: String = "control.self_link"

    /** Takes `{member}` and `{anchor}`. */
    const val LINKED_ALREADY: String = "control.linked_already"

    /** Takes `{chat}`. */
    const val ANCHOR_WITH_MEMBERS: String = "control.anchor_with_members"

    /** Takes `{folder}`. */
    const val FOLDER_NOT_ABSOLUTE: String = "control.folder_not_absolute"

    /** Takes `{folder}`. */
    const val FOLDER_MISSING: String = "control.folder_missing"

    /** Takes `{folder}` and `{reason}`. */
    const val FOLDER_PROTECTED: String = "control.folder_protected"

    /** Takes `{folder}`. */
    const val FOLDER_BY_REFERENCE: String = "control.folder_by_reference"

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
