package org.foedusprogramme.alexandrite.internal.path

import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.util.UUID

/**
 * Replaces the entry [target] with a file of what [write] writes, moved there atomically once it is written and synced.
 *
 * The new file takes the permissions of the file it replaces.
 */
public fun writeAtomically(target: Path, write: (OutputStream) -> Unit) {
    val directory = target.parent ?: throw IOException("$target names no file in a directory.")
    val temporary = directory.resolve(".${target.fileName}.${UUID.randomUUID()}.tmp")
    try {
        FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val output = Channels.newOutputStream(channel).buffered()
            write(Unclosed(output))
            output.flush()
            channel.force(true)
        }
        copyPermissions(target, temporary)
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

private fun copyPermissions(from: Path, to: Path) {
    val source = Files.getFileAttributeView(from, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        ?: return
    val permissions = try {
        source.readAttributes().permissions()
    } catch (e: NoSuchFileException) {
        return
    }
    Files.setPosixFilePermissions(to, permissions)
}

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
