package org.unitedsoftware.alexandrite.channel.telegram.i18n

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

    const val STREAMING_DESCRIPTION: String = "command.streaming.description"

    const val STREAMING_USAGE: String = "streaming.usage"

    /** Takes `{mode}` and `{quote}`. */
    const val STREAMING_NOW: String = "streaming.now"

    const val STREAMING_BOT_DEFAULT: String = "streaming.bot_default"

    const val STREAMING_ELSEWHERE: String = "streaming.elsewhere"

    /** Takes `{id}`. */
    const val APPROVAL_TITLE: String = "approval.title"

    /** Takes `{agent}`, the agents from the root turn's down to the sub-agent that asks. */
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

    /** Takes `{bytes}` and `{readable}`, the size of a file sent. */
    const val APPROVAL_SIZE: String = "approval.size"

    /** Takes `{sha256}`. */
    const val APPROVAL_SHA256: String = "approval.sha256"

    /** Takes `{chat}`, where a file is sent. */
    const val APPROVAL_DESTINATION: String = "approval.destination"

    const val APPROVAL_CAPTION: String = "approval.caption"

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

    const val ACTIVITY_VERB_READ_PRESENT: String = "activity.verb.read.present"
    const val ACTIVITY_VERB_READ_PAST: String = "activity.verb.read.past"
    const val ACTIVITY_VERB_LIST_PRESENT: String = "activity.verb.list.present"
    const val ACTIVITY_VERB_LIST_PAST: String = "activity.verb.list.past"
    const val ACTIVITY_VERB_SEARCH_PRESENT: String = "activity.verb.search.present"
    const val ACTIVITY_VERB_SEARCH_PAST: String = "activity.verb.search.past"
    const val ACTIVITY_VERB_EDIT_PRESENT: String = "activity.verb.edit.present"
    const val ACTIVITY_VERB_EDIT_PAST: String = "activity.verb.edit.past"
    const val ACTIVITY_VERB_DELETE_PRESENT: String = "activity.verb.delete.present"
    const val ACTIVITY_VERB_DELETE_PAST: String = "activity.verb.delete.past"
    const val ACTIVITY_VERB_MOVE_PRESENT: String = "activity.verb.move.present"
    const val ACTIVITY_VERB_MOVE_PAST: String = "activity.verb.move.past"
    const val ACTIVITY_VERB_EXECUTE_PRESENT: String = "activity.verb.execute.present"
    const val ACTIVITY_VERB_EXECUTE_PAST: String = "activity.verb.execute.past"
    const val ACTIVITY_VERB_FETCH_PRESENT: String = "activity.verb.fetch.present"
    const val ACTIVITY_VERB_FETCH_PAST: String = "activity.verb.fetch.past"
    const val ACTIVITY_VERB_SEND_PRESENT: String = "activity.verb.send.present"
    const val ACTIVITY_VERB_SEND_PAST: String = "activity.verb.send.past"
    const val ACTIVITY_VERB_THINK_PRESENT: String = "activity.verb.think.present"
    const val ACTIVITY_VERB_THINK_PAST: String = "activity.verb.think.past"
    const val ACTIVITY_VERB_OTHER_PRESENT: String = "activity.verb.other.present"
    const val ACTIVITY_VERB_OTHER_PAST: String = "activity.verb.other.past"

    const val ACTIVITY_NOUN_READ_ONE: String = "activity.noun.read.one"
    const val ACTIVITY_NOUN_READ_MANY: String = "activity.noun.read.many"
    const val ACTIVITY_NOUN_LIST_ONE: String = "activity.noun.list.one"
    const val ACTIVITY_NOUN_LIST_MANY: String = "activity.noun.list.many"
    const val ACTIVITY_NOUN_SEARCH_ONE: String = "activity.noun.search.one"
    const val ACTIVITY_NOUN_SEARCH_MANY: String = "activity.noun.search.many"
    const val ACTIVITY_NOUN_EDIT_ONE: String = "activity.noun.edit.one"
    const val ACTIVITY_NOUN_EDIT_MANY: String = "activity.noun.edit.many"
    const val ACTIVITY_NOUN_DELETE_ONE: String = "activity.noun.delete.one"
    const val ACTIVITY_NOUN_DELETE_MANY: String = "activity.noun.delete.many"
    const val ACTIVITY_NOUN_MOVE_ONE: String = "activity.noun.move.one"
    const val ACTIVITY_NOUN_MOVE_MANY: String = "activity.noun.move.many"
    const val ACTIVITY_NOUN_EXECUTE_ONE: String = "activity.noun.execute.one"
    const val ACTIVITY_NOUN_EXECUTE_MANY: String = "activity.noun.execute.many"
    const val ACTIVITY_NOUN_FETCH_ONE: String = "activity.noun.fetch.one"
    const val ACTIVITY_NOUN_FETCH_MANY: String = "activity.noun.fetch.many"
    const val ACTIVITY_NOUN_SEND_ONE: String = "activity.noun.send.one"
    const val ACTIVITY_NOUN_SEND_MANY: String = "activity.noun.send.many"
    const val ACTIVITY_NOUN_THINK_ONE: String = "activity.noun.think.one"
    const val ACTIVITY_NOUN_THINK_MANY: String = "activity.noun.think.many"
    const val ACTIVITY_NOUN_OTHER_ONE: String = "activity.noun.other.one"
    const val ACTIVITY_NOUN_OTHER_MANY: String = "activity.noun.other.many"

    /** Takes `{noun}`, the aggregate line of a kind whose calls were all refused. */
    const val ACTIVITY_SUMMARY_NOT_RUN: String = "activity.summary.not_run"

    /** Takes `{count}`, the detail lines dropped to fit one message. */
    const val ACTIVITY_OMITTED_ONE: String = "activity.omitted.one"

    /** Takes `{count}`. */
    const val ACTIVITY_OMITTED_MANY: String = "activity.omitted.many"

    /** Takes `{tool}` and `{title}`. */
    const val ACTIVITY_WAITING_APPROVAL: String = "activity.call.waiting_approval"

    /** Takes `{tool}` and `{title}`. */
    const val ACTIVITY_NOT_RUN: String = "activity.call.not_run"

    const val ACTIVITY_BACKGROUNDED: String = "activity.call.backgrounded"

    /** Takes `{code}`. */
    const val ACTIVITY_EXIT: String = "activity.call.exit"

    const val ACTIVITY_FAILED: String = "activity.call.failed"

    /** Takes `{added}` and `{removed}`. */
    const val ACTIVITY_DIFF: String = "activity.call.diff"

    /** Takes `{agent}`, the profile of the sub-agent that a call runs. */
    const val ACTIVITY_DELEGATING: String = "activity.delegate.present"

    /** Takes `{agent}`. */
    const val ACTIVITY_DELEGATED: String = "activity.delegate.past"

    /** Takes `{agent}` and `{run}`, a sub-agent whose run goes on in the background. */
    const val ACTIVITY_RUN: String = "activity.delegate.run"

    /** Takes `{agent}` and `{line}`, the line of a call that the sub-agent [agent] made. */
    const val ACTIVITY_BY_AGENT: String = "activity.call.by_agent"

    private val VERBS: Map<String, Pair<String, String>> = mapOf(
        "read" to (ACTIVITY_VERB_READ_PRESENT to ACTIVITY_VERB_READ_PAST),
        "list" to (ACTIVITY_VERB_LIST_PRESENT to ACTIVITY_VERB_LIST_PAST),
        "search" to (ACTIVITY_VERB_SEARCH_PRESENT to ACTIVITY_VERB_SEARCH_PAST),
        "edit" to (ACTIVITY_VERB_EDIT_PRESENT to ACTIVITY_VERB_EDIT_PAST),
        "delete" to (ACTIVITY_VERB_DELETE_PRESENT to ACTIVITY_VERB_DELETE_PAST),
        "move" to (ACTIVITY_VERB_MOVE_PRESENT to ACTIVITY_VERB_MOVE_PAST),
        "execute" to (ACTIVITY_VERB_EXECUTE_PRESENT to ACTIVITY_VERB_EXECUTE_PAST),
        "fetch" to (ACTIVITY_VERB_FETCH_PRESENT to ACTIVITY_VERB_FETCH_PAST),
        "send" to (ACTIVITY_VERB_SEND_PRESENT to ACTIVITY_VERB_SEND_PAST),
        "think" to (ACTIVITY_VERB_THINK_PRESENT to ACTIVITY_VERB_THINK_PAST),
        "other" to (ACTIVITY_VERB_OTHER_PRESENT to ACTIVITY_VERB_OTHER_PAST),
    )

    private val NOUNS: Map<String, Pair<String, String>> = mapOf(
        "read" to (ACTIVITY_NOUN_READ_ONE to ACTIVITY_NOUN_READ_MANY),
        "list" to (ACTIVITY_NOUN_LIST_ONE to ACTIVITY_NOUN_LIST_MANY),
        "search" to (ACTIVITY_NOUN_SEARCH_ONE to ACTIVITY_NOUN_SEARCH_MANY),
        "edit" to (ACTIVITY_NOUN_EDIT_ONE to ACTIVITY_NOUN_EDIT_MANY),
        "delete" to (ACTIVITY_NOUN_DELETE_ONE to ACTIVITY_NOUN_DELETE_MANY),
        "move" to (ACTIVITY_NOUN_MOVE_ONE to ACTIVITY_NOUN_MOVE_MANY),
        "execute" to (ACTIVITY_NOUN_EXECUTE_ONE to ACTIVITY_NOUN_EXECUTE_MANY),
        "fetch" to (ACTIVITY_NOUN_FETCH_ONE to ACTIVITY_NOUN_FETCH_MANY),
        "send" to (ACTIVITY_NOUN_SEND_ONE to ACTIVITY_NOUN_SEND_MANY),
        "think" to (ACTIVITY_NOUN_THINK_ONE to ACTIVITY_NOUN_THINK_MANY),
        "other" to (ACTIVITY_NOUN_OTHER_ONE to ACTIVITY_NOUN_OTHER_MANY),
    )

    /**
     * The present or past tense verb of one [org.unitedsoftware.alexandrite.sdk.tool.ToolActivity] kind's id, that of
     * `other` for a kind this version does not know.
     */
    fun activityVerb(kind: String, past: Boolean): String {
        val (present, pastKey) = VERBS[kind] ?: VERBS.getValue(OTHER)
        return if (past) pastKey else present
    }

    /** The noun phrase, with `{count}`, of one activity kind's id; some languages use it for one and for many alike. */
    fun activityNoun(kind: String, many: Boolean): String {
        val (one, manyKey) = NOUNS[kind] ?: NOUNS.getValue(OTHER)
        return if (many) manyKey else one
    }

    /** The key, with `{count}`, of the line that says how many earlier calls were dropped to fit one message. */
    fun activityOmitted(many: Boolean): String = if (many) ACTIVITY_OMITTED_MANY else ACTIVITY_OMITTED_ONE
}

/** The activity kind whose texts stand in for a kind this version does not know. */
private const val OTHER = "other"
