package org.unitedsoftware.alexandrite.app

import org.unitedsoftware.alexandrite.runtime.i18n.readTexts
import java.util.Locale

/** Where the command's own texts lie on the class path. */
internal const val CLI_TEXTS = "alexandrite/cli-i18n"

internal const val ENGLISH_TEXTS = "en"
internal const val CHINESE_TEXTS = "zh-CN"

private val PLACEHOLDER = Regex("\\{([A-Za-z][A-Za-z0-9_]*)}")

/** A text of [key] filled with [args], told in the language of the texts that resolve it. */
internal class Text(val key: String, vararg val args: Pair<String, Any?>)

/** The command's texts for people in [language], English where it has none. */
internal class CliTexts private constructor(
    val language: String,
    private val texts: Map<String, String>,
    private val english: Map<String, String>,
) {
    /** The text of [key] with its `{name}` placeholders filled from [args]. */
    operator fun invoke(key: String, vararg args: Pair<String, Any?>): String {
        val template = texts[key] ?: english[key] ?: error("The command has no text $key")
        return PLACEHOLDER.replace(template) { match ->
            val name = match.groupValues[1]
            val arg = args.firstOrNull { it.first == name } ?: return@replace match.value
            when (val value = arg.second) {
                is Text -> invoke(value.key, *value.args)
                else -> "$value"
            }
        }
    }

    operator fun invoke(text: Text): String = invoke(text.key, *text.args)

    fun has(key: String): Boolean = key in english

    val chinese: Boolean get() = language == CHINESE_TEXTS

    companion object {
        val ENGLISH: CliTexts by lazy { CliTexts(ENGLISH_TEXTS, read(ENGLISH_TEXTS), read(ENGLISH_TEXTS)) }

        val CHINESE: CliTexts by lazy { CliTexts(CHINESE_TEXTS, read(CHINESE_TEXTS), ENGLISH.english) }

        /** Chinese when the locale the environment names, else [locale], is Chinese, English otherwise. */
        fun of(environment: Map<String, String>, locale: Locale): CliTexts {
            val named = listOf("LC_ALL", "LC_MESSAGES", "LANG").firstNotNullOfOrNull { name ->
                environment[name]?.takeIf { it.isNotEmpty() }
            }
            val chinese = named?.startsWith("zh") ?: (locale.language == "zh")
            return if (chinese) CHINESE else ENGLISH
        }

        /** The texts of [language] as the class path holds them. */
        fun read(language: String): Map<String, String> {
            val name = "$CLI_TEXTS/$language.properties"
            val url = checkNotNull(CliTexts::class.java.classLoader.getResource(name)) { "$name is missing" }
            return readTexts(url)
        }
    }
}
