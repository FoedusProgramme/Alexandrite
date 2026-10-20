package org.unitedsoftware.alexandrite.commands.support

import java.util.Locale

/** The keyword that returns a setting to its default. */
internal const val RESET: String = "reset"

internal fun words(arguments: String): List<String> = arguments.trim().split(WHITESPACE).filter(String::isNotEmpty)

internal fun String.isKeyword(keyword: String): Boolean = equals(keyword, ignoreCase = true)

internal fun commandWord(name: String): String = name.lowercase(Locale.ROOT)

private val WHITESPACE = Regex("\\s+")
