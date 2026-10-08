package org.foedusprogramme.alexandrite.testkit.store

import org.foedusprogramme.alexandrite.sdk.store.MediaInfo
import org.foedusprogramme.alexandrite.sdk.store.MediaStore
import org.foedusprogramme.alexandrite.sdk.transcript.MediaId
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.transcript.StoredMedia
import java.security.MessageDigest
import java.util.HexFormat

internal class MemoryMedia(private val data: MemoryData) : MediaStore {
    override suspend fun put(bytes: ByteArray, kind: MediaKind, mediaType: String): StoredMedia {
        require(mediaType.isNotBlank()) { "Media needs a media type." }
        val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val id = MediaId(newId())
        data.locked { now ->
            media[id] = StoredBytes(MediaInfo(id, kind, mediaType, bytes.size.toLong(), sha256, now), bytes.copyOf())
        }
        return StoredMedia(id)
    }

    override suspend fun read(id: MediaId): ByteArray? = data.locked { media[id]?.bytes?.copyOf() }

    override suspend fun info(id: MediaId): MediaInfo? = data.locked { media[id]?.info }
}
