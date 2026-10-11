package org.foedusprogramme.alexandrite.sdk.i18n

import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.di.PluginLocal

/**
 * The plugin's texts for people: per language, the host's text catalog overrides the plugin's UTF-8 resources
 * `alexandrite/i18n/<plugin id>/<language tag>.properties`, which hold the defaults (`en` at least).
 *
 * The host may change its catalog while the runtime runs, so a text is looked up each time it is shown.
 * Text the model reads is not localized.
 */
@PluginLocal
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface Texts {
    /**
     * The text [key] in the first language that has it among [language] and its shorter tags, the host's language and
     * its shorter tags, and `en`, with each `{name}` replaced by the argument of that name; [key] itself when none does.
     */
    public fun text(key: String, language: LanguageTag?, vararg args: Pair<String, Any?>): String
}
