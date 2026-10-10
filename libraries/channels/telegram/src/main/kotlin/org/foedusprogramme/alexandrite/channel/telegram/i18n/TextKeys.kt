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
}
