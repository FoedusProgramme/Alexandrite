package org.foedusprogramme.alexandrite.runtime

import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds

/** Texts the host gives plugins in place of their own, by plugin id, language and key. */
public class TextCatalog private constructor(private val entries: Map<String, Map<String, Map<String, String>>>) {
    /** The ids of the plugins it holds texts of. */
    public val plugins: Set<String> get() = entries.keys

    public fun languages(plugin: String): Set<LanguageTag> =
        entries[plugin].orEmpty().keys.mapTo(linkedSetOf(), ::LanguageTag)

    /** The texts of [plugin] in [language], by key. */
    public fun texts(plugin: String, language: LanguageTag): Map<String, String> =
        entries[plugin]?.get(language.value).orEmpty()

    /** The text [key] of [plugin] in [language], null when it holds none. */
    public fun text(plugin: String, language: LanguageTag, key: String): String? = text(plugin, language.value, key)

    internal fun text(plugin: String, tag: String, key: String): String? = entries[plugin]?.get(tag)?.get(key)

    public fun toBuilder(): Builder = Builder(entries)

    override fun equals(other: Any?): Boolean = other is TextCatalog && entries == other.entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String =
        entries.entries.joinToString(prefix = "TextCatalog(", postfix = ")") { (plugin, languages) ->
            "$plugin=${languages.keys}"
        }

    public class Builder internal constructor(entries: Map<String, Map<String, Map<String, String>>>) {
        private val entries = entries.mapValuesTo(sortedMapOf()) { (_, languages) ->
            languages.mapValuesTo(sortedMapOf()) { (_, texts) -> texts.toSortedMap() }
        }

        public fun text(plugin: String, language: LanguageTag, key: String, template: String): Builder =
            texts(plugin, language, mapOf(key to template))

        /** Sets the texts of [plugin] in [language], keeping its other keys. */
        public fun texts(plugin: String, language: LanguageTag, texts: Map<String, String>): Builder = apply {
            require(PluginIds.PATTERN.matches(plugin)) { "Malformed plugin id '$plugin'." }
            entries.getOrPut(plugin, ::sortedMapOf).getOrPut(language.value, ::sortedMapOf).putAll(texts)
        }

        public fun build(): TextCatalog {
            val kept = entries.mapValues { (_, languages) ->
                languages.filterValues { it.isNotEmpty() }.mapValues { (_, texts) -> texts.toMap() }
            }
            return TextCatalog(kept.filterValues { it.isNotEmpty() })
        }
    }

    public companion object {
        public val EMPTY: TextCatalog = TextCatalog(emptyMap())

        public fun builder(): Builder = Builder(emptyMap())
    }
}
