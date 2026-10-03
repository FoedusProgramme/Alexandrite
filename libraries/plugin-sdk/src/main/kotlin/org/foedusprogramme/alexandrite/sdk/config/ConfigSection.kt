package org.foedusprogramme.alexandrite.sdk.config

/** Marks a `@Serializable` class as the config at the dot-separated [path] below its plugin's config root. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class ConfigSection(val path: String = "")
