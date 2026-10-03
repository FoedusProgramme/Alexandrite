package org.foedusprogramme.alexandrite.sdk.di

/** Resolves instances by [Key]. */
public interface Resolver {
    /** The instance of the single binding for [key]. */
    public fun <T : Any> get(key: Key<T>): T

    /** The instance of the single binding for [key], or null when nothing binds it. */
    public fun <T : Any> getOrNull(key: Key<T>): T?

    /** Every multibinding contribution for [key] by plugin and declaration order, overrides and extras last. */
    public fun <T : Any> getAll(key: Key<T>): List<T>

    /** A [Lazy] that resolves [key] on first access. */
    public fun <T : Any> lazy(key: Key<T>): Lazy<T>

    /** A function that resolves [key] on every call. */
    public fun <T : Any> provider(key: Key<T>): () -> T
}
