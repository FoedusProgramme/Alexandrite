package org.foedusprogramme.alexandrite.sdk.plugin

/** Marks the entry class of a plugin. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Plugin(
    val name: String,
    val description: String = "",
    /** Ids of the plugins this one needs. */
    val requires: Array<String> = [],
)
