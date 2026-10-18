package org.unitedsoftware.alexandrite.tools.fs

import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** An entry that a walk reached, with its path [relative] to the walk's root. */
internal class Entry(val path: Path, val relative: String, val attributes: BasicFileAttributes)

/**
 * Walks the tree under a directory in name order, depth first, without following links: skips what the floor refuses
 * [agent] and version-control directories, and visits at most [maxEntries] entries.
 */
internal class TreeWalk(
    private val floor: HardFloor,
    private val agent: AgentId,
    private val conversation: ConversationId?,
    private val maxEntries: Int = MAX_ENTRIES,
    private val list: (Path) -> DirectoryStream<Path> = { Files.newDirectoryStream(it) },
) {
    /** Each entry the floor refused, with the kind of protected place it lies in. */
    private val protected = mutableListOf<Pair<Path, String>>()

    private val unreadable = mutableListOf<Path>()

    /** Whether the walk stopped at [maxEntries]. */
    var exhausted = false
        private set

    /** Notes on what the walk left out, empty when it left out nothing. */
    val notes: List<String>
        get() = buildList {
            if (exhausted) add("The search stopped after $maxEntries entries: give a narrower 'path'.")
            named("Skipped protected places", protected.map { (path, reason) -> "$path ($reason)" })?.let(::add)
            named("Could not read", unreadable.map { "$it" })?.let(::add)
        }

    private var visited = 0

    /** Visits each entry under [root], a canonical directory, until [visit] returns false. */
    fun walk(root: Path, visit: (Entry) -> Boolean) {
        val pending = ArrayDeque<Pair<Iterator<Path>, String>>()
        pending.addLast(children(root) to "")
        while (pending.isNotEmpty()) {
            val (children, prefix) = pending.last()
            if (!children.hasNext()) {
                pending.removeLast()
                continue
            }
            if (Thread.interrupted()) throw InterruptedException()
            if (visited++ == maxEntries) {
                exhausted = true
                return
            }
            val child = children.next()
            val name = child.fileName.toString()
            val denied = floor.checkRead(child, agent, conversation) as? FloorCheck.Denied
            if (denied != null) {
                protected.add(child to denied.reason)
                continue
            }
            val attributes = try {
                attributes(child)
            } catch (e: IOException) {
                unreadable.add(child)
                continue
            }
            if (attributes.isDirectory && name in VERSION_CONTROL) continue
            val entry = Entry(child, prefix + name, attributes)
            if (!visit(entry)) return
            if (attributes.isDirectory) pending.addLast(children(child) to "${entry.relative}/")
        }
    }

    /** The entries of [directory] in name order, as many as the walk may still visit and one more. */
    private fun children(directory: Path): Iterator<Path> = try {
        list(directory).use { entries ->
            entries.asSequence().take(maxEntries - visited + 1).sortedBy { it.fileName.toString() }.toList()
        }.iterator()
    } catch (e: IOException) {
        unreadable.add(directory)
        emptyList<Path>().iterator()
    } catch (e: DirectoryIteratorException) {
        unreadable.add(directory)
        emptyList<Path>().iterator()
    }
}

/** [items] after [title], at most [MAX_NAMED] of them, null when there are none. */
internal fun named(title: String, items: List<String>): String? {
    if (items.isEmpty()) return null
    val more = items.size - MAX_NAMED
    return "$title: ${items.take(MAX_NAMED).joinToString()}" + (if (more > 0) " and $more more" else "") + "."
}

/** The most entries a walk visits. */
internal const val MAX_ENTRIES = 100_000

/** The most paths a note names. */
internal const val MAX_NAMED = 20

private val VERSION_CONTROL = setOf(".git", ".hg", ".svn")
