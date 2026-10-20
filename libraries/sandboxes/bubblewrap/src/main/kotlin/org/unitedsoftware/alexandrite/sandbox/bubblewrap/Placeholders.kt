package org.unitedsoftware.alexandrite.sandbox.bubblewrap

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** The empty files that running sandboxes mounted placeholders on, removed when the last of them ends. */
internal class Placeholders {
    private val holders = HashMap<Path, Int>()

    /** [plan]'s mounts, planned while no other launch plans, with their placeholders held until [release]. */
    @Synchronized
    fun hold(plan: ((Path) -> Boolean) -> Mounts): Mounts {
        val mounts = plan { it in holders }
        mounts.placeholders.forEach { holders.merge(it, 1, Int::plus) }
        return mounts
    }

    @Synchronized
    fun release(placeholders: List<Path>) {
        for (path in placeholders) {
            val count = holders[path] ?: continue
            if (count > 1) {
                holders[path] = count - 1
                continue
            }
            holders.remove(path)
            try {
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (attributes.isRegularFile && attributes.size() == 0L) Files.delete(path)
            } catch (e: IOException) {
                // Gone already, or no longer ours to remove.
            }
        }
    }
}
