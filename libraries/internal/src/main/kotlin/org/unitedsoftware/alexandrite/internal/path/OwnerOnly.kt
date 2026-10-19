package org.unitedsoftware.alexandrite.internal.path

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Creates [directory] and its missing parents owner-only where the file system has POSIX permissions. */
public fun createOwnerOnly(directory: Path) {
    if (hasPosixPermissions(directory)) {
        Files.createDirectories(directory, OWNER_ONLY)
    } else {
        Files.createDirectories(directory)
    }
}

public fun hasPosixPermissions(path: Path): Boolean = "posix" in path.fileSystem.supportedFileAttributeViews()

private val OWNER_ONLY = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
