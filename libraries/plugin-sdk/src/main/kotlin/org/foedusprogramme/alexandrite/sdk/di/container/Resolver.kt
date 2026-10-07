package org.foedusprogramme.alexandrite.sdk.di.container

import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.di.Key

/** Resolves instances by [Key]. */
@InternalAlexandriteApi
public interface Resolver {
    /** The instance of the single binding for [key]. */
    public fun <T : Any> get(key: Key<T>): T

    /** The instance of the single binding for [key], or null when nothing binds it. */
    public fun <T : Any> getOrNull(key: Key<T>): T?

    /** Every multibinding contribution for [key] by plugin id and binding order, a child container's extras last. */
    public fun <T : Any> getAll(key: Key<T>): List<T>

    /** A [Lazy] that resolves [key] on first access. */
    public fun <T : Any> lazy(key: Key<T>): Lazy<T>

    /** A function that resolves [key] on every call. */
    public fun <T : Any> provider(key: Key<T>): () -> T
}
