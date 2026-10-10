package org.foedusprogramme.alexandrite.runtime.i18n

import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.TextCatalog
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * The texts of the plugins whose resources [classLoaders] read, by plugin id, over the catalog installed last, which
 * tell [listener] about each distinct problem once.
 */
internal class RuntimeTexts(
    classLoaders: Map<String, ClassLoader>,
    hostLanguage: LanguageTag,
    listener: TextProblemListener,
) {
    private val reported = ConcurrentHashMap.newKeySet<Pair<String, String>>()
    private val listener = TextProblemListener { plugin, problem ->
        if (reported.add(plugin to problem)) listener.onProblem(plugin, problem)
    }

    /** The catalog last given to [install]. */
    private var given: TextCatalog? = null

    @Volatile
    private var catalog: TextCatalog = TextCatalog.EMPTY

    private val plugins: Map<String, PluginTexts> = classLoaders.mapValues { (plugin, classLoader) ->
        PluginTexts(plugin, classLoader, hostLanguage, this.listener) { catalog }
    }

    fun of(plugin: String): Texts = plugins.getValue(plugin)

    /** Puts [catalog] in place of the one before, without the texts whose placeholders differ from the plugin's. */
    fun install(catalog: TextCatalog) {
        synchronized(this) {
            if (catalog == given) return
            given = catalog
            this.catalog = checked(catalog)
        }
    }

    private fun checked(catalog: TextCatalog): TextCatalog {
        val kept = TextCatalog.builder()
        for (plugin in catalog.plugins) {
            val defaults = plugins[plugin]?.own(ENGLISH)
            val undefined = sortedSetOf<String>()
            if (defaults == null) listener.onProblem(plugin, UNKNOWN_PLUGIN)
            for (language in catalog.languages(plugin)) {
                for ((key, text) in catalog.texts(plugin, language)) {
                    val default = defaults?.get(key)
                    if (defaults != null && default == null) undefined += key
                    if (default == null || placeholderNames(text) == placeholderNames(default)) {
                        kept.text(plugin, language, key, text)
                    } else {
                        listener.onProblem(plugin, mismatch(key, language, text, default))
                    }
                }
            }
            for (key in undefined) {
                listener.onProblem(plugin, "does not define the host's text '$key' in its $ENGLISH$TEXTS_SUFFIX")
            }
        }
        return kept.build()
    }
}

/** The [Texts] of [plugin] read through [classLoader] over [catalog] for code that runs no runtime, which logs. */
@InternalAlexandriteApi
public fun standaloneTexts(
    plugin: String,
    classLoader: ClassLoader,
    hostLanguage: LanguageTag,
    catalog: TextCatalog,
): Texts {
    val texts = RuntimeTexts(mapOf(plugin to classLoader), hostLanguage) { owner, problem ->
        LoggerFactory.getLogger(AlexandriteRuntime::class.java).warn("plugin {} {}", owner, problem)
    }
    texts.install(catalog)
    return texts.of(plugin)
}

private const val UNKNOWN_PLUGIN = "is not in the plugin set; the host's texts of it go unused"

private fun mismatch(key: String, language: LanguageTag, text: String, default: String): String =
    "ignores the host's text '$key' ($language): its placeholders ${braced(placeholderNames(text))} are not those " +
        "of $ENGLISH$TEXTS_SUFFIX, ${braced(placeholderNames(default))}"

internal fun braced(names: Set<String>): String = names.joinToString(prefix = "[", postfix = "]") { "{$it}" }
