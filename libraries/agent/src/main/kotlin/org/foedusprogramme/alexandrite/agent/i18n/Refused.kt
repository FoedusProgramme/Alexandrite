package org.foedusprogramme.alexandrite.agent.i18n

/** A change that breaks a rule, whose message for the user is the text [key] with [args]. */
internal class Refused(val key: String, vararg val args: Pair<String, Any?>) : IllegalArgumentException(key)
