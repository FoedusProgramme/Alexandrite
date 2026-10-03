package org.foedusprogramme.alexandrite.sdk.runtime

/** Marks a type a host may resolve from a started runtime. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class HostApi
