package org.foedusprogramme.alexandrite.internal.path

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/** How a volume compares names. */
public enum class NameCase {
    SENSITIVE,

    /** Names that differ only in case name one entry. */
    INSENSITIVE,

    /** No name on the volume could be probed. */
    UNKNOWN,
}

/** Finds how the volume of a real path compares names, by probing each volume once. */
public class VolumeProbe {
    private val known = ConcurrentHashMap<Any, NameCase>()

    /** How the volume that holds [existing], an existing real path, compares names. */
    public fun nameCase(existing: Path): NameCase {
        val volume = volume(existing) ?: return NameCase.UNKNOWN
        known[volume]?.let { return it }
        return probe(existing, volume).also { if (it != NameCase.UNKNOWN) known[volume] = it }
    }

    /** Looks up a name stored on [volume] with one letter's case changed: a name on [existing]'s path, else in it. */
    private fun probe(existing: Path, volume: Any): NameCase {
        var entry = existing
        while (true) {
            val parent = entry.parent ?: break
            if (volume(parent) == volume) lookUp(entry)?.let { return it }
            entry = parent
        }
        if (!Files.isDirectory(existing, LinkOption.NOFOLLOW_LINKS)) return NameCase.UNKNOWN
        try {
            Files.newDirectoryStream(existing).use { entries ->
                for (child in entries.take(MAX_LISTED)) lookUp(child)?.let { return it }
            }
        } catch (e: IOException) {
            return NameCase.UNKNOWN
        }
        return NameCase.UNKNOWN
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

    /** The device of [path], or its file store where the platform has no device numbers. */
    private fun volume(path: Path): Any? = try {
        Files.getAttribute(path, "unix:dev", LinkOption.NOFOLLOW_LINKS)
    } catch (e: UnsupportedOperationException) {
        fileStore(path)
    } catch (e: IllegalArgumentException) {
        fileStore(path)
    } catch (e: IOException) {
        null
    }

    private fun fileStore(path: Path): Any? = try {
        Files.getFileStore(path)
    } catch (e: IOException) {
        null
    }

    private companion object {
        const val MAX_LISTED = 64
    }
}
