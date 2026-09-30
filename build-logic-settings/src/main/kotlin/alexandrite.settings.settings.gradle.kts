// Settings plugin `alexandrite.settings`: includes every module found at the locations of the layout
// (AlexandriteLayout.kt).
// Fails when a build file sits at no location or a slot holds none.

import org.foedusprogramme.alexandrite.buildlogic.AlexandriteLayout
import org.foedusprogramme.alexandrite.buildlogic.BuildFileScan

val buildFileDirectories = providers.of(BuildFileScan::class.java) {
    parameters.rootDirectory.set(settingsDir)
}.get()

val discovery = AlexandriteLayout.discover(buildFileDirectories)
discovery.failure?.let { throw GradleException(it) }
discovery.modules.forEach { module -> include(module.path) }
