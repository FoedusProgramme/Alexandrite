package org.unitedsoftware.alexandrite.sandbox.bubblewrap

import org.unitedsoftware.alexandrite.sdk.sandbox.Boundary
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries

/** The bubblewrap arguments that build a boundary's file system, and the empty files they create on the host. */
internal class Mounts(val arguments: List<String>, val placeholders: List<Path>)

/**
 * The mounts of [boundary] over the host's root, each path made real by [real].
 *
 * A protected name is bound read-only where it exists down to [depth] directories below a writable place, and an empty
 * read-only file (a placeholder) keeps it from being made right in one; [held] tells the placeholders of other
 * sandboxes. The places between a writable place and what it may not write are bound onto themselves, which keeps them
 * from being moved away.
 */
internal fun mounts(
    boundary: Boundary,
    real: (Path) -> Path,
    held: (Path) -> Boolean = { false },
    /** Sockets bound into the sandbox at their own paths. */
    sockets: List<Path> = emptyList(),
    depth: Int = PROTECTED_DEPTH,
): Mounts {
    val writable = boundary.writable.map(real).distinct()
    val readDenied = boundary.readDenied.map(real).distinct()
    val readableEntries = boundary.readable.map(real).distinct()
    val writeDenied = boundary.writeDenied.map(real).distinct()
    val (found, missing) = protectedNames(boundary.protectedNames.distinct(), writable, readDenied, depth, held, real)
    val writes = writable.map { it to true } + (writeDenied + readDenied + found).map { it to false }
    val reads = readDenied.map { it to false } + readableEntries.map { it to true }
    val placeholders = missing.filter { innermost(it.parent, writes, false) }.sortedWith(DEPTH_FIRST)
    val unwritable = (writeDenied + found).filter { point ->
        innermost(point.parent ?: point, writes, false) || writable.any { it.startsWith(point) }
    } + placeholders
    val allWrites = writes + placeholders.map { it to false }
    val pins = unwritable.flatMap { point ->
        generateSequence(point.parent) { it.parent }.takeWhile { innermost(it, allWrites, false) }
    }
    val points = (writable + readDenied + readableEntries + unwritable + pins).distinct()
        .filter { it.parent != null && !it.startsWith(DEV) && !it.startsWith(PROC) }
        .sortedWith(DEPTH_FIRST)
    val root = if (innermost(ROOT, allWrites, false)) "--bind" else "--ro-bind"
    val arguments: MutableList<String> = mutableListOf(root, "/", "/", "--dev", "/dev", "--proc", "/proc")
    val remounts = mutableListOf<Path>()
    for (point in points) {
        if (point in placeholders) {
            arguments += listOf("--ro-bind", NULL, point.toString())
            continue
        }
        val directory = point.isDirectory(LinkOption.NOFOLLOW_LINKS)
        if ((!directory && !Files.exists(point, LinkOption.NOFOLLOW_LINKS)) || point.isSymbolicLink()) continue
        val name = point.toString()
        val readable = innermost(point, reads, true)
        if (!readable && directory) remounts.add(point)
        arguments += when {
            !readable && directory -> listOf("--tmpfs", name)
            !readable -> listOf("--ro-bind", NULL, name)
            innermost(point, allWrites, false) -> listOf("--bind", name, name)
            else -> listOf("--ro-bind", name, name)
        }
    }
    for (socket in sockets) arguments += listOf("--bind", socket.toString(), socket.toString())
    for (point in remounts.reversed()) arguments += listOf("--remount-ro", point.toString())
    return Mounts(arguments, placeholders)
}

/**
 * The places of [names] that exist down to [depth] directories below the [writable] ones, and the first missing part
 * of each that is missing right in one.
 */
private fun protectedNames(
    names: List<String>,
    writable: List<Path>,
    readDenied: List<Path>,
    depth: Int,
    held: (Path) -> Boolean,
    real: (Path) -> Path,
): Pair<Set<Path>, Set<Path>> {
    val found = mutableSetOf<Path>()
    val missing = mutableSetOf<Path>()
    var budget = MAX_DIRECTORIES
    fun visit(directory: Path, level: Int) {
        for (name in names) {
            val target = directory.resolve(name)
            when {
                target.isSymbolicLink() -> real(target).takeIf { link -> writable.any { link.startsWith(it) } }
                    ?.let(found::add)

                Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !held(target) -> found.add(target)

                level == 0 -> firstMissing(directory, name, held)?.let(missing::add)
            }
        }
        if (level >= depth) return
        val children = try {
            directory.listDirectoryEntries().sorted()
        } catch (e: IOException) {
            return
        }
        for (child in children) {
            if (budget <= 0) return
            if (!child.isDirectory(LinkOption.NOFOLLOW_LINKS) || readDenied.any { child.startsWith(it) }) continue
            budget--
            visit(child, level + 1)
        }
    }
    writable.filter { it.isDirectory(LinkOption.NOFOLLOW_LINKS) }.forEach { visit(it, 0) }
    return found to missing
}

/** The first component of [name] below [directory] that is missing, null when a file or link stands in the way. */
private fun firstMissing(directory: Path, name: String, held: (Path) -> Boolean): Path? {
    var current = directory
    for (part in name.split('/')) {
        current = current.resolve(part)
        if (held(current) || !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return current
        if (!current.isDirectory(LinkOption.NOFOLLOW_LINKS)) return null
    }
    return null
}

/** The rule of the innermost of [rules] that holds [path], denial winning a tie, [default] for none. */
private fun innermost(path: Path, rules: List<Pair<Path, Boolean>>, default: Boolean): Boolean =
    rules.filter { path.startsWith(it.first) }
        .maxWithOrNull(compareBy<Pair<Path, Boolean>>({ it.first.nameCount }, { !it.second }))
        ?.second ?: default

private val DEPTH_FIRST = compareBy<Path>({ it.nameCount }, { it.toString() })

private val ROOT = Path.of("/")
private val DEV = Path.of("/dev")
private val PROC = Path.of("/proc")
private const val NULL = "/dev/null"

/** How many directories below a writable place protected names are looked for. */
internal const val PROTECTED_DEPTH: Int = 3

/** The most directories looked into for protected names per launch. */
private const val MAX_DIRECTORIES = 20_000
