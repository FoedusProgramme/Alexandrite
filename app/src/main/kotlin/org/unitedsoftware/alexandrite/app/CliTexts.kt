package org.unitedsoftware.alexandrite.app

import org.unitedsoftware.alexandrite.runtime.i18n.readTexts
import java.util.Locale

/** Where the command's own texts lie on the class path. */
internal const val CLI_TEXTS = "alexandrite/cli-i18n"

internal const val ENGLISH_TEXTS = "en"
internal const val CHINESE_TEXTS = "zh-CN"

private val PLACEHOLDER = Regex("\\{([A-Za-z][A-Za-z0-9_]*)}")

private fun han(char: Char): Boolean = Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN ||
    Character.UnicodeBlock.of(char) == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
    Character.UnicodeBlock.of(char) == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS

/** A text of [key] filled with [args], told in the language of the texts that resolve it. */
internal class Text(val key: String, vararg val args: Pair<String, Any?>)

/** The command's texts for people in [language], English where it has none. */
internal class CliTexts private constructor(
    val language: String,
    private val texts: Map<String, String>,
    private val english: Map<String, String>,
) {
    /**
     * The text of [key] with its `{name}` placeholders filled from [args]. In Chinese, a value joins a Chinese
     * character beside it without the space between them when it meets it with a Chinese character.
     */
    operator fun invoke(key: String, vararg args: Pair<String, Any?>): String {
        val template = texts[key] ?: english[key] ?: error("The command has no text $key")
        val out = StringBuilder()
        var end = 0
        for (match in PLACEHOLDER.findAll(template)) {
            out.append(template, end, match.range.first)
            end = match.range.last + 1
            val arg = args.firstOrNull { it.first == match.groupValues[1] }
            if (arg == null) {
                out.append(match.value)
                continue
            }
            val value = when (val given = arg.second) {
                is Text -> invoke(given.key, *given.args)
                else -> "$given"
            }
            if (chinese && value.isNotEmpty()) {
                if (out.endsWith(' ') && out.length > 1 && han(out[out.length - 2]) && han(value.first())) {
                    out.setLength(out.length - 1)
                }
                val next = template.getOrNull(end + 1)
                if (template.getOrNull(end) == ' ' && next != null && han(next) && han(value.last())) end++
            }
            out.append(value)
        }
        return "${out.append(template, end, template.length)}"
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
