package org.unitedsoftware.alexandrite.internal.text

/** [word] as a POSIX shell reads it back: itself when a shell neither splits nor expands it, else in single quotes. */
public fun shellWord(word: String): String =
    if (word.isNotEmpty() && word.all { it.isLetterOrDigit() || it in PLAIN }) word else singleQuoted(word)

/** [words] as one command line, each written by [shellWord]. */
public fun shellLine(words: List<String>): String = words.joinToString(" ", transform = ::shellWord)

/** [text] in single quotes, each of its own single quotes closed, escaped and opened again. */
public fun singleQuoted(text: String): String = "'" + text.replace("'", "'\\''") + "'"

/** The characters besides letters and digits that a shell takes as part of a word without expanding them. */
private const val PLAIN = "-_./=:,+@%"
