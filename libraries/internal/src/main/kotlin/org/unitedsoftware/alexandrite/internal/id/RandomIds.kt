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
