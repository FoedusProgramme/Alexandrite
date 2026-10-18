package org.unitedsoftware.alexandrite.store.sqlite

import org.unitedsoftware.alexandrite.sdk.transcript.MediaId
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.StoredMedia
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry

internal fun TranscriptEntry.mediaParts(): List<MediaPart> = when (this) {
    is UserEntry -> parts.filterIsInstance<MediaPart>()
    is ToolResultEntry -> content.filterIsInstance<MediaPart>()
    else -> emptyList()
}

/** The media in the store that the entry refers to. */
internal fun TranscriptEntry.storedMedia(): Set<MediaId> =
    mediaParts().mapNotNullTo(LinkedHashSet()) { (it.source as? StoredMedia)?.id }
