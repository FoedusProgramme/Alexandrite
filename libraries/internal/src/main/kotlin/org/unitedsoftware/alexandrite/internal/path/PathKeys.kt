package org.unitedsoftware.alexandrite.internal.path

import java.io.File
import java.text.Normalizer
import java.util.Locale

/** The macOS volume that every firmlink points into. */
internal const val DATA_VOLUME: String = "/System/Volumes/Data"

/**
 * [path] in NFC, with the separators of a Windows host as `/`, and fully case-folded when [ignoreCase], as APFS
 * compares names.
 *
 * A key compares names, not spellings, so a host whose paths use `\` gives the same keys as one whose paths use `/`:
 * a key then ends up under another key's `/` on either host. A `\` stays a name character wherever the host allows it
 * in a name.
 *
 * Uppercasing first folds what lowercasing alone keeps (`ſ`, the Kelvin sign), and folding can decompose, so the result
 * is NFC again.
 */
internal fun nameKey(path: String, ignoreCase: Boolean): String {
    val unified = if (WINDOWS_SEPARATOR) path.replace('\\', '/') else path
    val nfc = Normalizer.normalize(unified, Normalizer.Form.NFC)
    if (!ignoreCase) return nfc
    return Normalizer.normalize(nfc.uppercase(Locale.ROOT).lowercase(Locale.ROOT), Normalizer.Form.NFC)
}

/** Whether this host separates the elements of a path with `\`. */
private val WINDOWS_SEPARATOR: Boolean = File.separatorChar == '\\'

/** [path] with every leading [DATA_VOLUME] folded back to `/`, ignoring case. */
internal fun foldDataVolume(path: String): String {
    var current = path
    while (current.startsWith(DATA_VOLUME, ignoreCase = true)) {
        val rest = current.substring(DATA_VOLUME.length)
        current = when {
            rest.isEmpty() || rest == "/" -> return "/"
            rest.startsWith("/") -> rest
            else -> return current
        }
    }
    return current
}

internal fun isAtOrUnder(path: String, root: String): Boolean =
    path == root || path.startsWith(if (root.endsWith("/")) root else "$root/")
