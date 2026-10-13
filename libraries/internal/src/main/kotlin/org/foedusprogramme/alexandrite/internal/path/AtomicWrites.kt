package org.foedusprogramme.alexandrite.internal.path

import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * Replaces the entry [target] with a file of what [write] writes, moved there atomically once it is written and synced.
 *
 * The new file takes the permissions of the file it replaces, or is owner-only when there is none to take. A host
 * that keeps no permissions of that kind makes the file its own way.
 */
public fun writeAtomically(target: Path, write: (OutputStream) -> Unit) {
    val directory = target.parent ?: throw IOException("$target names no file in a directory.")
    val temporary = directory.resolve(temporaryName(target.fileName.toString()))
    val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    val attribute = targetPermissions(target)?.let { PosixFilePermissions.asFileAttribute(it) }
    try {
        val opened = attribute?.let { FileChannel.open(temporary, options, it) }
            ?: FileChannel.open(temporary, options)
        opened.use { channel ->
            val output = Channels.newOutputStream(channel).buffered()
            write(Unclosed(output))
            output.flush()
            channel.force(true)
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: Throwable) {
        try {
            Files.deleteIfExists(temporary)
        } catch (suppressed: IOException) {
            e.addSuppressed(suppressed)
        }
        throw e
    }
    syncDirectory(directory)
}

/** [target]'s own permissions, owner-only when it has none to take, or null where the host keeps none. */
private fun targetPermissions(target: Path): Set<PosixFilePermission>? {
    val view = Files.getFileAttributeView(target, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        ?: return null
    val existing = try {
        view.readAttributes().permissions()
    } catch (e: IOException) {
        null
    }
    return existing ?: OWNER_ONLY
}

/** A short temporary name for [name] that fits a filesystem's name length limit. */
private fun temporaryName(name: String): String {
    val random = UUID.randomUUID().toString().replace("-", "").take(8)
    return ".${name.take(16)}.$random.tmp"
}

private val OWNER_ONLY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")

/** Syncs the entries of [directory] where the platform can. */
private fun syncDirectory(directory: Path) {
    try {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    } catch (e: IOException) {
        return
    }
}

/** [output], which the writer may close without closing it. */
private class Unclosed(output: OutputStream) : FilterOutputStream(output) {
    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
    }

    override fun close() {
        flush()
    }
}
