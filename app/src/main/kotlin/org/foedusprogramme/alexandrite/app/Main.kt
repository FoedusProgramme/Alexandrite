package org.foedusprogramme.alexandrite.app

import org.foedusprogramme.alexandrite.runtime.PluginSet
import org.slf4j.LoggerFactory
import java.util.Properties

// Placeholder entry point, replaced by the host program in a later task.

private val logger = LoggerFactory.getLogger("org.foedusprogramme.alexandrite.app.Main")

private const val VERSION_RESOURCE = "/alexandrite-version.properties"

internal val alexandriteVersion: String by lazy {
    val stream = checkNotNull(object {}.javaClass.getResourceAsStream(VERSION_RESOURCE)) {
        "$VERSION_RESOURCE is missing from the classpath"
    }
    val properties = Properties()
    stream.use { properties.load(it) }
    checkNotNull(properties.getProperty("version")) { "$VERSION_RESOURCE has no version" }
}

internal fun startupLine(plugins: PluginSet): String =
    "Alexandrite $alexandriteVersion starting with built-in plugins [${plugins.plugins.joinToString { it.info.id }}]"

fun main() {
    logger.info(startupLine(PluginSet.builtIn()))
}
