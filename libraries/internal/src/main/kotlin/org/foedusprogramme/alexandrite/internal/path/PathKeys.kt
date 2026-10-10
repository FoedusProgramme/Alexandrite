package org.foedusprogramme.alexandrite.internal.path

import java.text.Normalizer
import java.util.Locale

/** The macOS volume that every firmlink points into. */
internal const val DATA_VOLUME: String = "/System/Volumes/Data"

/**
 * [path] in NFC, and fully case-folded when [ignoreCase], as APFS compares names.
 *
 * Uppercasing first folds what lowercasing alone keeps (`ſ`, the Kelvin sign), and folding can decompose, so the result
 * is NFC again.
 */
internal fun nameKey(path: String, ignoreCase: Boolean): String {
    val nfc = Normalizer.normalize(path, Normalizer.Form.NFC)
    if (!ignoreCase) return nfc
    return Normalizer.normalize(nfc.uppercase(Locale.ROOT).lowercase(Locale.ROOT), Normalizer.Form.NFC)
}

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
