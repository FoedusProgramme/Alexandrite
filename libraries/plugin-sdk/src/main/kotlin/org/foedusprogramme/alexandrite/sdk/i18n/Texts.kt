package org.foedusprogramme.alexandrite.sdk.i18n

import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.di.PluginLocal

/**
 * The plugin's texts for people, read from its UTF-8 resources `alexandrite/i18n/<plugin id>/<language tag>.properties`.
 * Text the model reads is not localized.
 */
@PluginLocal
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface Texts {
    /**
     * The text [key] from the first file that holds it among [language] and its shorter tags, the host's language and
     * its shorter tags, and `en`, with each `{name}` replaced by the argument of that name; [key] itself when none does.
     */
    public fun text(key: String, language: LanguageTag?, vararg args: Pair<String, Any?>): String
}
