package org.foedusprogramme.alexandrite.internal.path

import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** An absolute path in the two forms that a check compares. */
public class CanonicalPath internal constructor(
    /** With `.` and `..` folded and `/proc/self` pinned to the process. */
    public val lexical: Path,
    /** The real path of the longest existing prefix with the rest appended, dangling links followed. */
    public val real: Path,
    /** How the volume that holds the existing prefix compares names. */
    public val nameCase: NameCase,
    private val firmlinks: Boolean,
) {
    /** The comparison keys of both forms, case-folded when [ignoreCase]. */
    internal fun keys(ignoreCase: Boolean): List<String> =
        listOf(unalias(nameKey(lexical.toString(), ignoreCase)), unalias(nameKey(real.toString(), ignoreCase)))
            .distinct()

    /** The real form as it names an entry: verbatim, or case-folded when [ignoreCase]. */
    internal fun realKey(ignoreCase: Boolean): String =
        unalias(if (ignoreCase) nameKey(real.toString(), true) else real.toString())

    private fun unalias(key: String): String = if (firmlinks) foldDataVolume(key) else key
}

/** Makes absolute paths canonical, lexically and through the filesystem. */
public class PathCanonicalizer(
    /** How the volume that holds an existing real path compares names. */
    private val nameCase: (Path) -> NameCase = VolumeProbe()::nameCase,
    /** Whether `/System/Volumes/Data/…` names `/…` too, as macOS firmlinks make it. */
    private val firmlinks: Boolean = IS_MAC,
    private val pid: Long = ProcessHandle.current().pid(),
) {
    public fun canonicalize(path: Path): CanonicalPath {
        require(path.isAbsolute && path.fileSystem == FileSystems.getDefault()) {
            "A path to canonicalize is absolute and of the default file system, was '$path'."
        }
        val resolved = resolve(path, 0)
        val nameCase = resolved.existing?.let(nameCase) ?: NameCase.UNKNOWN
        return CanonicalPath(pinned(path.normalize()), pinned(resolved.real), nameCase, firmlinks)
    }

    private class Resolved(val real: Path, val existing: Path?)

    /** [absolute] through the real path of its longest existing prefix, a dangling link followed by hand. */
    private fun resolve(absolute: Path, depth: Int): Resolved {
        var current: Path? = absolute
        val rest = ArrayDeque<String>()
        while (current != null) {
            val existing = realPath(current)
            if (existing != null) return Resolved(appended(existing, rest).normalize(), existing)
            if (depth < MAX_LINK_DEPTH && Files.isSymbolicLink(current)) {
                val target = try {
                    (current.parent ?: current).resolve(Files.readSymbolicLink(current))
                } catch (e: IOException) {
                    null
                }
                if (target != null) return resolve(appended(target, rest), depth + 1)
            }
            current.fileName?.let { rest.addFirst(it.toString()) }
            current = current.parent
        }
        return Resolved(absolute.normalize(), null)
    }

    private fun realPath(path: Path): Path? = try {
        path.toRealPath()
    } catch (e: IOException) {
        null
    }

    private fun appended(path: Path, parts: Iterable<String>): Path = parts.fold(path, Path::resolve)

    private fun pinned(path: Path): Path {
        val text = path.toString()
        val rest = when {
            text == "/proc/self" || text == "/proc/thread-self" -> ""
            text.startsWith("/proc/self/") -> text.removePrefix("/proc/self")
            text.startsWith("/proc/thread-self/") -> text.removePrefix("/proc/thread-self")
            else -> return path
        }
        return Path.of("/proc/$pid$rest")
    }

    private companion object {
        const val MAX_LINK_DEPTH = 40
        val IS_MAC = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("mac")
    }
}
