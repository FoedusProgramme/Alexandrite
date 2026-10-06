package org.foedusprogramme.alexandrite.sdk.di

/** A component overrides only the lifecycle steps it needs. */
public interface Lifecycle {
    /** Prepares without taking outside work. */
    public suspend fun onStart() {}

    /** Starts taking outside work. */
    public suspend fun onOpen() {}

    /** Stops taking new work. */
    public suspend fun onClose() {}

    /** Finishes the work in flight. */
    public suspend fun onDrain() {}

    /** Undoes [onStart]. */
    public fun onStop() {}

    /** Releases what the constructor acquired. */
    public fun onDestroy() {}
}
