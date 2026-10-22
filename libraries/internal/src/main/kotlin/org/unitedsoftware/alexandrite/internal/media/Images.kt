package org.unitedsoftware.alexandrite.internal.media

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.roundToInt

/** The IANA media type that [bytes]' magic bytes declare, null when none of them match. */
public fun imageType(bytes: ByteArray): String? = when {
    bytes.matches(0, PNG_SIGNATURE) -> "image/png"
    bytes.matches(0, JPEG_SIGNATURE) -> "image/jpeg"
    bytes.matches(0, GIF87A) || bytes.matches(0, GIF89A) -> "image/gif"
    bytes.matches(0, RIFF) && bytes.matches(8, WEBP) -> "image/webp"
    bytes.matches(0, BMP_SIGNATURE) -> "image/bmp"
    bytes.matches(0, TIFF_LE) || bytes.matches(0, TIFF_BE) -> "image/tiff"
    bytes.matches(4, FTYP) -> heicType(bytes)
    else -> null
}

/** An image ready to send a model: [bytes] of [mediaType], [width] by [height] pixels. */
public class NormalizedImage(
    public val bytes: ByteArray,
    public val mediaType: String,
    public val width: Int,
    public val height: Int,
)

/** The width and height [bytes] declare as decoded, without scaling, null when they cannot be read that cheaply. */
public fun imageDimensions(bytes: ByteArray): Pair<Int, Int>? {
    val sniffed = imageType(bytes) ?: return null
    if (sniffed == "image/webp") return webpDimensions(bytes)
    val opened = opened(bytes) ?: return null
    return (opened.width to opened.height).also {
        opened.reader.dispose()
        opened.stream.close()
    }
}

/**
 * [bytes] fit to send a model: at most [maxSide] pixels on the longer side and [maxBytes] in size, null when
 * [bytes] cannot be decoded as an image.
 *
 * An already-fitting png, jpeg or (non-animated) gif passes through unchanged. A webp passes through unchanged when
 * it already fits [maxBytes], else null: ImageIO has no built-in decoder to shrink it. Everything else is scaled and
 * re-encoded, PNG when it has an alpha channel and fits, else JPEG at a falling quality, the pixel size halved up to
 * four times if it still does not fit. An oversized animated gif becomes its first frame as PNG. Refuses (null)
 * images above 50 megapixels before decoding their raster.
 */
public fun normalizedImage(bytes: ByteArray, maxSide: Int = 1568, maxBytes: Int = 5_000_000): NormalizedImage? {
    val sniffed = imageType(bytes) ?: return null
    if (sniffed == "image/webp") return webpImage(bytes, maxBytes)
    val opened = opened(bytes) ?: return null
    val reader = opened.reader
    try {
        val width = opened.width
        val height = opened.height
        if (width.toLong() * height.toLong() > BOMB_PIXELS) return null
        val withinLimits = bytes.size <= maxBytes && maxOf(width, height) <= maxSide
        if (withinLimits && sniffed in PASSTHROUGH_TYPES) return NormalizedImage(bytes, sniffed, width, height)
        val decoded = try {
            reader.read(0)
        } catch (e: IOException) {
            return null
        }
        if (sniffed == "image/gif") {
            val target = scaledTo(decoded, maxSide)
            return NormalizedImage(encodePng(target), "image/png", target.width, target.height)
        }
        return encoded(decoded, maxSide, maxBytes)
    } finally {
        reader.dispose()
        opened.stream.close()
    }
}

/** An [ImageReader] set up to decode [bytes], with the [width] and [height] it already reports. */
private class OpenedReader(
    val reader: ImageReader,
    val stream: MemoryCacheImageInputStream,
    val width: Int,
    val height: Int,
)

/** [bytes] opened by the first [ImageReader] that claims them, null when none does or its header is unreadable. */
private fun opened(bytes: ByteArray): OpenedReader? {
    val stream = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
    val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: run {
        stream.close()
        return null
    }
    reader.input = stream
    return try {
        OpenedReader(reader, stream, reader.getWidth(0), reader.getHeight(0))
    } catch (e: IOException) {
        reader.dispose()
        stream.close()
        null
    }
}

/** [bytes] unchanged when within [maxBytes], else null: ImageIO cannot re-encode a webp it did not decode. */
private fun webpImage(bytes: ByteArray, maxBytes: Int): NormalizedImage? {
    if (bytes.size > maxBytes) return null
    val (width, height) = webpDimensions(bytes) ?: (0 to 0)
    return NormalizedImage(bytes, "image/webp", width, height)
}

/** The canvas size a webp's `VP8X`, `VP8L` or `VP8 ` chunk declares, null when it cannot be read that cheaply. */
private fun webpDimensions(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 30) return null
    return when (String(bytes, 12, 4, Charsets.US_ASCII)) {
        "VP8X" -> (readLe24(bytes, 24) + 1) to (readLe24(bytes, 27) + 1)
        "VP8L" -> readLe32(bytes, 21).let { packed -> ((packed and 0x3FFF) + 1) to (((packed shr 14) and 0x3FFF) + 1) }
        "VP8 " -> (readLe16(bytes, 26) and 0x3FFF) to (readLe16(bytes, 28) and 0x3FFF)
        else -> null
    }
}

/** [decoded] scaled to [maxSide], then PNG when it has alpha and fits [maxBytes], else JPEG at a falling quality. */
private fun encoded(decoded: BufferedImage, maxSide: Int, maxBytes: Int): NormalizedImage {
    val hasAlpha = decoded.colorModel.hasAlpha()
    val target = scaledTo(decoded, maxSide)
    if (hasAlpha) {
        val png = encodePng(target)
        if (png.size <= maxBytes) return NormalizedImage(png, "image/png", target.width, target.height)
    }
    var current = target
    var halvings = 0
    while (true) {
        for ((index, quality) in JPEG_QUALITY_LADDER.withIndex()) {
            val jpeg = encodeJpeg(current, quality)
            val lastTry = halvings == MAX_HALVINGS && index == JPEG_QUALITY_LADDER.lastIndex
            if (jpeg.size <= maxBytes || lastTry) {
                return NormalizedImage(jpeg, "image/jpeg", current.width, current.height)
            }
        }
        current = halved(current)
        halvings++
    }
}

private fun encodePng(image: BufferedImage): ByteArray {
    val output = ByteArrayOutputStream()
    ImageIO.write(image, "png", output)
    return output.toByteArray()
}

private fun encodeJpeg(image: BufferedImage, quality: Float): ByteArray {
    val writer = ImageIO.getImageWritersByFormatName("jpg").next()
    val params = writer.defaultWriteParam.apply {
        compressionMode = ImageWriteParam.MODE_EXPLICIT
        compressionQuality = quality
    }
    val output = ByteArrayOutputStream()
    try {
        ImageIO.createImageOutputStream(output).use { target ->
            writer.output = target
            writer.write(null, IIOImage(toOpaque(image), null, null), params)
        }
    } finally {
        writer.dispose()
    }
    return output.toByteArray()
}

/** [image] over a white background, its alpha channel dropped: a JPEG writer refuses one. */
private fun toOpaque(image: BufferedImage): BufferedImage {
    if (!image.colorModel.hasAlpha()) return image
    val opaque = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
    val g = opaque.createGraphics()
    g.color = Color.WHITE
    g.fillRect(0, 0, image.width, image.height)
    g.drawImage(image, 0, 0, null)
    g.dispose()
    return opaque
}

/** [image] unchanged when its longer side is at most [maxSide], else scaled proportionally down to it. */
private fun scaledTo(image: BufferedImage, maxSide: Int): BufferedImage {
    val longest = maxOf(image.width, image.height)
    if (longest <= maxSide) return image
    val scale = maxSide.toDouble() / longest
    return resized(image, maxOf(1, (image.width * scale).roundToInt()), maxOf(1, (image.height * scale).roundToInt()))
}

private fun halved(image: BufferedImage): BufferedImage =
    resized(image, maxOf(1, image.width / 2), maxOf(1, image.height / 2))

private fun resized(image: BufferedImage, width: Int, height: Int): BufferedImage {
    val type = if (image.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
    val scaled = BufferedImage(width, height, type)
    val g = scaled.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.drawImage(image, 0, 0, width, height, null)
    g.dispose()
    return scaled
}

private fun heicType(bytes: ByteArray): String? {
    if (bytes.size < 12) return null
    return when (String(bytes, 8, 4, Charsets.US_ASCII)) {
        in HEIC_BRANDS -> "image/heic"
        in HEIF_BRANDS -> "image/heif"
        else -> null
    }
}

private fun ByteArray.matches(offset: Int, signature: ByteArray): Boolean =
    size >= offset + signature.size && signature.indices.all { this[offset + it] == signature[it] }

private fun readLe16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

private fun readLe24(bytes: ByteArray, offset: Int): Int =
    readLe16(bytes, offset) or ((bytes[offset + 2].toInt() and 0xFF) shl 16)

private fun readLe32(bytes: ByteArray, offset: Int): Int =
    readLe24(bytes, offset) or ((bytes[offset + 3].toInt() and 0xFF) shl 24)

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
private val GIF87A = "GIF87a".toByteArray(Charsets.US_ASCII)
private val GIF89A = "GIF89a".toByteArray(Charsets.US_ASCII)
private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)
private val BMP_SIGNATURE = "BM".toByteArray(Charsets.US_ASCII)
private val TIFF_LE = byteArrayOf(0x49, 0x49, 0x2A, 0x00)
private val TIFF_BE = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A)
private val FTYP = "ftyp".toByteArray(Charsets.US_ASCII)
private val HEIC_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs")
private val HEIF_BRANDS = setOf("mif1", "msf1")
private val PASSTHROUGH_TYPES = setOf("image/png", "image/jpeg", "image/gif")
private val JPEG_QUALITY_LADDER = floatArrayOf(0.85f, 0.7f, 0.5f)
private const val MAX_HALVINGS = 4
private const val BOMB_PIXELS = 50_000_000L
