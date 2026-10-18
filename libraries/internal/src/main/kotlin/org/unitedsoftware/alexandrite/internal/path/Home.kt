package org.unitedsoftware.alexandrite.internal.path

/** The home directory of the user the process runs as, null when the JVM names none. */
public fun homeDirectory(): String? = System.getProperty("user.home")?.takeIf { it.isNotEmpty() }

/** [text] with a leading `~` (alone or before `/`) as [home]. */
public fun expandHome(text: String, home: String? = homeDirectory()): String = when {
    home.isNullOrEmpty() -> text
    text == "~" -> home
    text.startsWith("~/") -> home + text.substring(1)
    else -> text
}
