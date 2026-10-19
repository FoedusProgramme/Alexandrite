package org.unitedsoftware.alexandrite.runtime

import org.unitedsoftware.alexandrite.runtime.config.resolveConfig
import org.unitedsoftware.alexandrite.runtime.plugin.PluginSet
import org.unitedsoftware.alexandrite.runtime.plugin.pluginProblems
import org.unitedsoftware.alexandrite.sdk.config.ConfigSource
import org.unitedsoftware.alexandrite.sdk.problem.Problem

/** The problems that the stages PLUGINS and CONFIG of a runtime of [plugins] find in [config], none when both pass. */
public fun configProblems(plugins: PluginSet, config: ConfigSource): List<Problem> =
    pluginProblems(plugins).ifEmpty { resolveConfig(plugins.members, config, mutableListOf()).problems }
