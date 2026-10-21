package org.unitedsoftware.alexandrite.internal.id

import java.security.SecureRandom
import java.util.Base64

private val random = SecureRandom()

/** A random id of 22 URL-safe characters. */
public fun newId(): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** A random id of 8 lower-case letters and digits, without those that read alike, for people to type. */
public fun shortId(): String = String(CharArray(SHORT_LENGTH) { SHORT_CHARS[random.nextInt(SHORT_CHARS.length)] })

private const val SHORT_CHARS = "abcdefghijkmnopqrstuvwxyz23456789"
private const val SHORT_LENGTH = 8
