package org.foedusprogramme.alexandrite.runtime.lifecycle

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

private val OWNER_ONLY = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))

/** Creates [directory] and its missing parents owner-only where the file system has POSIX permissions. */
internal fun createOwnerOnly(directory: Path) {
    if ("posix" in directory.fileSystem.supportedFileAttributeViews()) {
        Files.createDirectories(directory, OWNER_ONLY)
    } else {
        Files.createDirectories(directory)
    }
}
