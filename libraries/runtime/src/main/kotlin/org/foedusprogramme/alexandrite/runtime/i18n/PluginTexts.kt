package org.foedusprogramme.alexandrite.runtime.i18n

import org.foedusprogramme.alexandrite.runtime.TextCatalog
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Told about the text problems of plugins. */
internal fun interface TextProblemListener {
    fun onProblem(plugin: String, problem: String)
}

/**
 * The [Texts] of [plugin]: for each language of the fallback, the [catalog]'s text, else the plugin's own resource
 * read through [classLoader].
 */
internal class PluginTexts(
    private val plugin: String,
    private val classLoader: ClassLoader,
    private val hostLanguage: LanguageTag,
    private val listener: TextProblemListener,
    private val catalog: () -> TextCatalog,
) : Texts {
    private val files = ConcurrentHashMap<String, Map<String, String>>()
    private val reported = ConcurrentHashMap.newKeySet<List<String>>()

    override fun text(key: String, language: LanguageTag?, vararg args: Pair<String, Any?>): String {
        val tags = fallbacks(language, hostLanguage)
        val catalog = catalog()
        val arguments = args.toMap()
        for (tag in tags) {
            val text = catalog.text(plugin, tag, key) ?: own(tag)[key] ?: continue
            return PLACEHOLDER.replace(text) { match ->
                val name = match.groupValues[1]
                if (name in arguments) return@replace arguments[name].toString()
                report(
                    listOf(key, name),
                    "got no argument {$name} for its text '$key' ($tag), which shows it as written",
                )
                match.value
            }
        }
        report(listOf(key), "has no text '$key' in ${tags.joinToString()}; the key is shown instead")
        return key
    }

    /** The plugin's own texts in [tag], empty when it has no file of it or the file cannot be read. */
    fun own(tag: String): Map<String, String> = files.computeIfAbsent(tag) {
        val path = textsPath(plugin, tag)
        val url = classLoader.getResource(path) ?: return@computeIfAbsent emptyMap()
        try {
            readTexts(url)
        } catch (e: IOException) {
            report(listOf(path), "cannot read $path, whose texts count as absent: $e")
            emptyMap()
        }
    }

    private fun report(subject: List<String>, problem: String) {
        if (reported.add(subject)) listener.onProblem(plugin, problem)
    }
}

/** The tags of the files a text is looked up in, in order. */
internal fun fallbacks(language: LanguageTag?, host: LanguageTag): List<String> =
    (listOfNotNull(language, host).flatMap(::withShorterTags) + ENGLISH).distinct()

/** [tag], then the tags left by dropping its last subtag one at a time. */
private fun withShorterTags(tag: LanguageTag): List<String> =
    generateSequence(tag.value) { it.substringBeforeLast('-', "").ifEmpty { null } }.toList()
