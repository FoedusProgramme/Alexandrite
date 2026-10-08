package org.foedusprogramme.alexandrite.testkit.store

import org.foedusprogramme.alexandrite.sdk.store.MediaInfo
import org.foedusprogramme.alexandrite.sdk.transcript.MediaId
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.testkit.StoreCheck
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

internal val MEDIA_CHECKS: List<StoreCheck> = listOf(
    StoreCheck("media are stored each under an id of its own") {
        val bytes = "a photo".encodeToByteArray()
        val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        open {
            val before = Instant.now()
            val image = media.put(bytes, MediaKind.IMAGE, "image/png")
            val file = media.put(bytes, MediaKind.FILE, "application/octet-stream")
            val after = Instant.now()

            expect(image.id != file.id) { "Two media were stored under the id ${image.id}." }
            val info = media.info(image.id) ?: fail("Media ${image.id} is not stored.")
            expectWithin(info.createdAt, before, after, "the time the media was stored")
            expectEqual(
                MediaInfo(image.id, MediaKind.IMAGE, "image/png", bytes.size.toLong(), sha256, info.createdAt),
                info,
                "the media",
            )
            expectEqual(MediaKind.FILE, media.info(file.id)?.kind, "the kind of the other media")
            expectEqual(bytes.toList(), media.read(file.id)?.toList(), "the bytes of the other media")
            expectEqual(null, media.info(MediaId("missing")), "unknown media")
            expectEqual(null, media.read(MediaId("missing")), "the bytes of unknown media")
        }
    },
    StoreCheck("media without a media type are refused") {
        open {
            expectThrows<IllegalArgumentException>("Storing media without a media type") {
                media.put(png, MediaKind.IMAGE, " ")
            }
        }
    },
    StoreCheck("media outlive the store") {
        val stored = open { media.put(png, MediaKind.of("sticker"), "image/webp") }
        val info = open { media.info(stored.id) }

        open {
            expectEqual(MediaKind.of("sticker"), info?.kind, "the kind this version does not know")
            expectEqual(png.toList(), media.read(stored.id)?.toList(), "the bytes")
        }
    },
)
