package org.foedusprogramme.alexandrite.channel.onebot.i18n

/** The keys of the OneBot channel's texts for people. */
internal object TextKeys {
    /** Takes `{id}`. */
    const val APPROVAL_TITLE: String = "approval.title"

    /** Takes `{agent}`. */
    const val APPROVAL_AGENT: String = "approval.agent"

    /** Takes `{chat}`. */
    const val APPROVAL_CHAT: String = "approval.chat"

    /** Takes `{requester}`. */
    const val APPROVAL_REQUESTER: String = "approval.requester"

    /** Takes `{reason}`. */
    const val APPROVAL_REASON: String = "approval.reason"

    /** Takes `{tool}` and `{risk}`. */
    const val APPROVAL_TOOL: String = "approval.tool"

    /** Takes `{path}`. */
    const val APPROVAL_FILE: String = "approval.file"

    /** Takes `{directory}`. */
    const val APPROVAL_DIRECTORY: String = "approval.directory"

    /** Takes `{program}`. */
    const val APPROVAL_PROGRAM: String = "approval.program"

    const val APPROVAL_PROGRAM_UNKNOWN: String = "approval.program_unknown"
    const val APPROVAL_OPAQUE: String = "approval.opaque"
    const val APPROVAL_ARGUMENTS: String = "approval.arguments"

    /** How an approver answers, which this channel takes as a command rather than as a press. */
    const val APPROVAL_ANSWER: String = "approval.answer"

    /** Takes `{rule}`. */
    const val APPROVAL_ALWAYS_RULE: String = "approval.always_rule"

    /** Takes `{id}` and `{name}`. */
    const val APPROVAL_OUTCOME_ALLOWED: String = "approval.outcome_allowed"

    /** Takes `{id}`, `{name}` and `{rule}`. */
    const val APPROVAL_OUTCOME_ALLOWED_ALWAYS: String = "approval.outcome_allowed_always"

    /** Takes `{id}` and `{name}`. */
    const val APPROVAL_OUTCOME_DENIED: String = "approval.outcome_denied"

    /** Takes `{id}`. */
    const val APPROVAL_OUTCOME_EXPIRED: String = "approval.outcome_expired"

    /** Takes `{id}`. */
    const val APPROVAL_OUTCOME_WITHDRAWN: String = "approval.outcome_withdrawn"

    const val APPROVAL_ADMINS_ONLY: String = "approval.admins_only"
    const val APPROVAL_NOT_PENDING: String = "approval.not_pending"
    const val APPROVAL_NOTHING_WAITS: String = "approval.nothing_waits"
    const val APPROVAL_LIST_TITLE: String = "approval.list_title"

    /** Takes `{id}`, `{tool}` and `{reason}`. */
    const val APPROVAL_LIST_ENTRY: String = "approval.list_entry"

    const val APPROVAL_LIST_HINT: String = "approval.list_hint"

    /** Takes `{ids}`. */
    const val APPROVAL_SEVERAL: String = "approval.several"
}
