package org.unitedsoftware.alexandrite.internal.hash

import java.io.InputStream
import java.security.MessageDigest
import java.util.HexFormat

public fun sha256(bytes: ByteArray): ByteArray = sha256Digest().digest(bytes)

/** The SHA-256 of [bytes] in lowercase hex. */
public fun sha256Hex(bytes: ByteArray): String = hex(sha256(bytes))

/** The SHA-256 of [text]'s UTF-8 bytes in lowercase hex. */
public fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

/** The SHA-256 of what is left of [input] in lowercase hex, read without holding it. */
public fun sha256Hex(input: InputStream): String {
    val digest = sha256Digest()
    val buffer = ByteArray(BUFFER_BYTES)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return hex(digest.digest())
}

public fun sha256Digest(): MessageDigest = MessageDigest.getInstance("SHA-256")

/** [bytes] in lowercase hex. */
public fun hex(bytes: ByteArray): String = HexFormat.of().formatHex(bytes)

private const val BUFFER_BYTES = 64 * 1024
