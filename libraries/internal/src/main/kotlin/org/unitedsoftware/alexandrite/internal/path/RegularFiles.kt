package org.unitedsoftware.alexandrite.internal.path

import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Thrown for a file that turned out to be no regular file with one hard link when it was opened. */
public class NotRegularFileException(file: Path) :
    IOException("$file is no regular file with one hard link, so it is not read.")

/** How many hard links [file] has, 1 where the file system does not tell. */
public fun hardLinks(file: Path): Int = try {
    Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Int
} catch (e: UnsupportedOperationException) {
    1
} catch (e: IllegalArgumentException) {
    1
}

/**
 * Opens [file] to read, checked as a regular file before the open and after it, and as one with one hard link when
 * [oneLink].
 */
public fun openRegularFile(file: Path, oneLink: Boolean): InputStream {
    val before = regular(file, oneLink)
    val channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    try {
        if (regular(file, oneLink).fileKey() != before.fileKey()) throw NotRegularFileException(file)
        return Channels.newInputStream(channel)
    } catch (e: Throwable) {
        channel.close()
        throw e
    }
}

/** The attributes of [file], once it is a regular file, with one hard link when [oneLink]. */
private fun regular(file: Path, oneLink: Boolean): BasicFileAttributes {
    val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (!attributes.isRegularFile || (oneLink && hardLinks(file) > 1)) throw NotRegularFileException(file)
    return attributes
}
