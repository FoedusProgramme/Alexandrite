package org.foedusprogramme.alexandrite.testkit

import org.foedusprogramme.alexandrite.runtime.TextCatalog
import org.foedusprogramme.alexandrite.runtime.i18n.ENGLISH
import org.foedusprogramme.alexandrite.runtime.i18n.TEXTS_SUFFIX
import org.foedusprogramme.alexandrite.runtime.i18n.placeholderNames
import org.foedusprogramme.alexandrite.runtime.i18n.readTexts
import org.foedusprogramme.alexandrite.runtime.i18n.resourceFiles
import org.foedusprogramme.alexandrite.runtime.i18n.standaloneTexts
import org.foedusprogramme.alexandrite.runtime.i18n.textsDirectory
import org.foedusprogramme.alexandrite.runtime.i18n.textsPath
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import java.io.IOException
import java.net.URL

/**
 * The texts of the plugin [plugin] on [classLoader] over [catalog] as the runtime reads them, where [language] is the
 * host's language.
 */
public fun testTexts(
    plugin: String = "test",
    language: LanguageTag = LanguageTag(ENGLISH),
    catalog: TextCatalog = TextCatalog.EMPTY,
    classLoader: ClassLoader = contextClassLoader(),
): Texts = standaloneTexts(plugin, classLoader, language, catalog)

/**
 * Checks that every language file of the plugin [plugin] on [classLoader] holds the keys of its `en.properties`, each
 * with the same placeholders, and returns the languages it has texts in.
 */
public fun assertTextsComplete(plugin: String, classLoader: ClassLoader = contextClassLoader()): List<LanguageTag> {
    val directory = textsDirectory(plugin)
    val english = textsPath(plugin, ENGLISH)
    val first = englishFile(plugin, classLoader)
    val reference = defaults(plugin, first)
    val problems = mutableListOf<String>()
    val languages = mutableListOf<LanguageTag>()
    for (file in resourceFiles(first, english, directory).filter { '/' !in it && it.endsWith(TEXTS_SUFFIX) }) {
        val copies = copies(classLoader, "$directory/$file")
        if (copies.size > 1) problems += "$file is on the class path ${copies.size} times: ${copies.joinToString()}"
        val tag = file.removeSuffix(TEXTS_SUFFIX)
        val canonical = try {
            LanguageTag.of(tag)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (canonical?.value != tag) {
            val rename = canonical?.let { " (rename it $it$TEXTS_SUFFIX)" }.orEmpty()
            problems += "$file is not named by a canonical language tag$rename"
            continue
        }
        languages += canonical
        if (tag == ENGLISH) continue
        try {
            problems += compared(file, reference, readTexts(copies.first()))
        } catch (e: IOException) {
            problems += "$file cannot be read: $e"
        }
    }
    if (problems.isNotEmpty()) throw incomplete("The texts of plugin '$plugin' in $directory", problems)
    return languages
}

/**
 * Checks that every language of [catalog] holds the keys of the `en.properties` of the plugin [plugin] on
 * [classLoader], each with the same placeholders, and returns the languages the catalog has texts of the plugin in.
 */
public fun assertTextsComplete(
    plugin: String,
    catalog: TextCatalog,
    classLoader: ClassLoader = contextClassLoader(),
): List<LanguageTag> {
    val reference = defaults(plugin, englishFile(plugin, classLoader))
    val languages = catalog.languages(plugin).toList()
    if (languages.isEmpty()) throw AssertionError("The catalog holds no texts of plugin '$plugin'.")
    val problems = languages.flatMap { compared("$it", reference, catalog.texts(plugin, it)) }
    if (problems.isNotEmpty()) throw incomplete("The catalog's texts of plugin '$plugin'", problems)
    return languages
}

private fun compared(file: String, reference: Map<String, String>, texts: Map<String, String>): List<String> {
    val missing = (reference.keys - texts.keys).sorted().map { "$file lacks '$it'" }
    val extra = (texts.keys - reference.keys).sorted().map { "$file has '$it', which $ENGLISH$TEXTS_SUFFIX lacks" }
    val mismatched = (reference.keys intersect texts.keys).sorted().mapNotNull { key ->
        val expected = placeholderNames(reference.getValue(key))
        val actual = placeholderNames(texts.getValue(key))
        if (expected == actual) return@mapNotNull null
        "$file has the placeholders ${braced(actual)} in '$key', $ENGLISH$TEXTS_SUFFIX ${braced(expected)}"
    }
    return missing + extra + mismatched
}

private fun braced(names: Set<String>): String = names.joinToString(prefix = "[", postfix = "]") { "{$it}" }

private fun defaults(plugin: String, url: URL): Map<String, String> = try {
    readTexts(url)
} catch (e: IOException) {
    throw AssertionError("Cannot read ${textsPath(plugin, ENGLISH)}: $e", e)
}

private fun englishFile(plugin: String, classLoader: ClassLoader): URL =
    copies(classLoader, textsPath(plugin, ENGLISH)).firstOrNull()
        ?: throw AssertionError(
            "Plugin '$plugin' has no texts: ${textsPath(plugin, ENGLISH)} is not on the class path.",
        )

private fun incomplete(what: String, problems: List<String>): AssertionError =
    AssertionError("$what are incomplete:" + problems.joinToString("") { "\n- $it" })

private fun copies(classLoader: ClassLoader, path: String): List<URL> =
    classLoader.getResources(path).toList().distinctBy { it.toExternalForm() }

private fun contextClassLoader(): ClassLoader =
    Thread.currentThread().contextClassLoader ?: PluginHarness::class.java.classLoader
