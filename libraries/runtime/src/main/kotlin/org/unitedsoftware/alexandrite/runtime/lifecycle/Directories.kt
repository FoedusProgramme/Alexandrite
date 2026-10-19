package org.unitedsoftware.alexandrite.runtime.lifecycle

import org.unitedsoftware.alexandrite.internal.path.hasPosixPermissions
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

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
