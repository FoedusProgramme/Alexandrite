package org.unitedsoftware.alexandrite.testkit.store

import org.unitedsoftware.alexandrite.internal.hash.sha256Hex
import org.unitedsoftware.alexandrite.internal.id.newId
import org.unitedsoftware.alexandrite.sdk.store.MediaInfo
import org.unitedsoftware.alexandrite.sdk.store.MediaStore
import org.unitedsoftware.alexandrite.sdk.transcript.MediaId
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.StoredMedia

internal class MemoryMedia(private val data: MemoryData) : MediaStore {
    override suspend fun put(bytes: ByteArray, kind: MediaKind, mediaType: String): StoredMedia {
        require(mediaType.isNotBlank()) { "Media needs a media type." }
        val sha256 = sha256Hex(bytes)
        val id = MediaId(newId())
        data.locked { now ->
            media[id] = StoredBytes(MediaInfo(id, kind, mediaType, bytes.size.toLong(), sha256, now), bytes.copyOf())
        }
        return StoredMedia(id)
    }

    override suspend fun read(id: MediaId): ByteArray? = data.locked { media[id]?.bytes?.copyOf() }

    override suspend fun info(id: MediaId): MediaInfo? = data.locked { media[id]?.info }
}
