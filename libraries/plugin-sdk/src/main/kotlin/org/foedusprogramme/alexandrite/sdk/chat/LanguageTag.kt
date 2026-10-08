package org.foedusprogramme.alexandrite.sdk.chat

import kotlinx.serialization.Serializable
import java.util.IllformedLocaleException
import java.util.Locale

/** A BCP 47 language tag in its canonical form, such as `zh-CN`. */
@JvmInline
@Serializable
public value class LanguageTag(public val value: String) {
    init {
        require(canonical(value) == value) {
            "Malformed language tag '$value': it must be a BCP 47 tag in canonical form. " +
                "Use LanguageTag.of to normalize it."
        }
    }

    override fun toString(): String = value

    public companion object {
        /** The canonical tag of [text], which may use `_` for `-`. */
        public fun of(text: String): LanguageTag = LanguageTag(
            canonical(text.trim().replace('_', '-'))
                ?: throw IllegalArgumentException("Malformed language tag '$text'."),
        )
    }
}

private fun canonical(text: String): String? = if (text.isEmpty()) {
    null
} else {
    try {
        Locale.Builder().setLanguageTag(text).build().toLanguageTag()
    } catch (e: IllformedLocaleException) {
        null
    }
}
