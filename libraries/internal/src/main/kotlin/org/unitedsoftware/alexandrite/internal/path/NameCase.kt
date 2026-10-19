package org.unitedsoftware.alexandrite.internal.path

import org.unitedsoftware.alexandrite.internal.collection.BoundedMap
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

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
    /** The file key of [entry], read without following a link, null where the file system keeps none. */
    private val fileKey: (entry: Path) -> Any? = { attributes(it).fileKey() },
    /** How [directory] compares names, looked up through [entry] in it, null when that cannot tell. */
    private val directoryCase: (directory: Path, entry: Path?) -> NameCase? = ::directoryCase,
) {
    private val known = BoundedMap<Path, Probed>(MAX_KNOWN)

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

    /** The case of [directory], answered by path where its file system keeps no file keys. */
    private fun case(directory: Path, entry: Path?): NameCase {
        cached(directory)?.takeIf { it.key == null }?.let { return it.case }
        val key = try {
            fileKey(directory)
        } catch (e: IOException) {
            return NameCase.UNKNOWN
        }
        cached(directory)?.takeIf { it.key == key }?.let { return it.case }
        val found = if (key == null) NameCase.UNKNOWN else directoryCase(directory, entry) ?: NameCase.UNKNOWN
        synchronized(known) { known[directory] = Probed(key, found) }
        return found
    }

    private fun cached(directory: Path): Probed? = synchronized(known) { known[directory] }

    private class Probed(val key: Any?, val case: NameCase)

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
