package org.foedusprogramme.alexandrite.channel.telegram.i18n

/** The keys of the Telegram channel's texts for people. */
internal object TextKeys {
    /** Takes `{code}` and `{minutes}`. */
    const val PAIRING_CODE: String = "pairing.code"

    /** Takes `{command}`. */
    const val REFUSED_ADMIN: String = "refused.admin"

    /** Takes `{command}`. */
    const val REFUSED_ADMIN_HERE: String = "refused.admin_here"

    /** Takes `{capacity}`. */
    const val QUEUE_FULL: String = "refused.queue_full"

    const val NO_AGENT: String = "refused.no_agent"
    const val UNSUPPORTED: String = "unsupported.message"
    const val UNFINISHED: String = "shutdown.unfinished"

    /** Takes `{id}`. */
    const val APPROVAL_TITLE: String = "approval.title"

    /** Takes `{agent}`. */
    const val APPROVAL_AGENT: String = "approval.agent"

    /** Takes `{chat}`. */
    const val APPROVAL_CHAT: String = "approval.chat"

    /** Takes `{requester}`. */
    const val APPROVAL_REQUESTER: String = "approval.requester"

    const val APPROVAL_NOBODY: String = "approval.nobody"

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
    const val APPROVAL_ALLOW: String = "approval.allow"

    /** Takes `{rule}`. */
    const val APPROVAL_ALWAYS: String = "approval.always"

    const val APPROVAL_DENY: String = "approval.deny"

    /** Takes `{name}`. */
    const val APPROVAL_ALLOWED: String = "approval.allowed"

    /** Takes `{name}` and `{rule}`. */
    const val APPROVAL_ALLOWED_ALWAYS: String = "approval.allowed_always"

    /** Takes `{name}`. */
    const val APPROVAL_DENIED: String = "approval.denied"

    const val APPROVAL_EXPIRED: String = "approval.expired"
    const val APPROVAL_WITHDRAWN: String = "approval.withdrawn"
    const val APPROVAL_ADMINS_ONLY: String = "approval.admins_only"
    const val APPROVAL_NOT_PENDING: String = "approval.not_pending"
}
