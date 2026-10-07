package org.foedusprogramme.alexandrite.runtime.lifecycle

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

private val OWNER_ONLY = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))

/** Creates [directory] and its missing parents owner-only where the file system has POSIX permissions. */
internal fun createOwnerOnly(directory: Path) {
    if (hasPosixPermissions(directory)) {
        Files.createDirectories(directory, OWNER_ONLY)
    } else {
        Files.createDirectories(directory)
    }
}

/** Whether users other than its owner may write to [directory], false when that cannot be read. */
internal fun writableByOthers(directory: Path): Boolean {
    if (!hasPosixPermissions(directory)) return false
    val permissions = try {
        Files.getPosixFilePermissions(directory)
    } catch (e: IOException) {
        return false
    }
    return PosixFilePermission.GROUP_WRITE in permissions || PosixFilePermission.OTHERS_WRITE in permissions
}

private fun hasPosixPermissions(path: Path): Boolean = "posix" in path.fileSystem.supportedFileAttributeViews()
