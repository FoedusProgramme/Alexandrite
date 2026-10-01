package org.foedusprogramme.alexandrite.sdk.config

import kotlinx.serialization.Serializable

/** A config value that is masked wherever it is printed. */
@Serializable
@JvmInline
public value class Secret(private val value: String) {
    public fun reveal(): String = value

    override fun toString(): String = "Secret(***)"
}
