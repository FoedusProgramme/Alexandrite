package org.unitedsoftware.alexandrite.sdk.plugin

/**
 * Marks the entry class of a plugin.
 *
 * A plugin may ship pages of the agent's manual, which the model reads on demand, as UTF-8 resources
 * `alexandrite/manual/<plugin id>/<topic>.md`: English Markdown about the plugin, a topic of lowercase words joined by
 * hyphens, a `# Title` line first and a one-sentence summary as the next line.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Plugin(
    val name: String,
    val description: String = "",
    /** Ids of the plugins this one needs. */
    val requires: Array<String> = [],
)
