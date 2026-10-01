package org.foedusprogramme.alexandrite.sdk.di

/** Thrown when a [Container] cannot be built or used. */
public class DiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
