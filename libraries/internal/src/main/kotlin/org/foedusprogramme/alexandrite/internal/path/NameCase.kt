package org.foedusprogramme.alexandrite.internal.path

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/** How the directories of a path compare names. */
public enum class NameCase {
    SENSITIVE,

    /** Names that differ only in case name one entry. */
    INSENSITIVE,

    /** The directories differ, or one of them could not be probed. */
    UNKNOWN,
}

/** Finds how the directories of a real path compare names, by probing each directory once. */
public class NameCaseProbe(
    /** How [directory] compares names, looked up through [entry] in it, null when that cannot tell. */
    private val directoryCase: (directory: Path, entry: Path?) -> NameCase? = ::directoryCase,
) {
    private val known = ConcurrentHashMap<Any, NameCase>()

    /** How the directories from the root down to [existing], an existing real path, compare names. */
    public fun nameCase(existing: Path): NameCase {
        val probes = buildList {
            if (Files.isDirectory(existing, LinkOption.NOFOLLOW_LINKS)) add(existing to null)
            var entry = existing
            while (true) {
                val parent = entry.parent ?: break
                add(parent to entry)
                entry = parent
            }
        }
        return probes.map { (directory, entry) -> case(directory, entry) }.toSet().singleOrNull() ?: NameCase.UNKNOWN
    }

    private fun case(directory: Path, entry: Path?): NameCase {
        val key = try {
            attributes(directory).fileKey()
        } catch (e: IOException) {
            return NameCase.UNKNOWN
        }
        key?.let { known[it] }?.let { return it }
        val found = directoryCase(directory, entry) ?: return NameCase.UNKNOWN
        if (key != null) {
            if (known.size >= MAX_KNOWN) known.clear()
            known[key] = found
        }
        return found
    }

    private companion object {
        const val MAX_KNOWN = 4096
    }
}

/** Looks up [entry], or else an entry of [directory]'s listing, with one letter's case changed. */
private fun directoryCase(directory: Path, entry: Path?): NameCase? {
    entry?.let(::lookUp)?.let { return it }
    try {
        Files.newDirectoryStream(directory).use { entries ->
            for (child in entries.take(MAX_LISTED)) lookUp(child)?.let { return it }
        }
    } catch (e: IOException) {
        return null
    }
    return null
}

/** Whether [entry] is found under its name with one ASCII letter's case changed, null when that cannot tell. */
private fun lookUp(entry: Path): NameCase? {
    val name = entry.fileName?.toString() ?: return null
    val index = name.indexOfFirst { it in 'a'..'z' || it in 'A'..'Z' }
    if (index < 0) return null
    val letter = name[index]
    val flipped = if (letter.isUpperCase()) letter.lowercaseChar() else letter.uppercaseChar()
    val variant = entry.resolveSibling(name.substring(0, index) + flipped + name.substring(index + 1))
    val key: Any = try {
        attributes(entry).fileKey() ?: return null
    } catch (e: IOException) {
        return null
    }
    val other = try {
        attributes(variant).fileKey()
    } catch (e: NoSuchFileException) {
        return NameCase.SENSITIVE
    } catch (e: IOException) {
        return null
    }
    return if (other == key) NameCase.INSENSITIVE else NameCase.SENSITIVE
}

private fun attributes(entry: Path): BasicFileAttributes =
    Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

private const val MAX_LISTED = 64
