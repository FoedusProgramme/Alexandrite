import com.diffplug.gradle.spotless.SpotlessTask
import org.foedusprogramme.alexandrite.buildlogic.AlexandriteLayout
import org.foedusprogramme.alexandrite.buildlogic.BuildFileScan

plugins {
    id("com.diffplug.spotless")
}

val projectPaths = providers.of(BuildFileScan::class.java) { parameters.rootDirectory = layout.settingsDirectory }
    .map { directories -> listOf(":") + AlexandriteLayout.discover(directories).modules.map { it.path } }
    .get()
val cleanTasks = projectPaths.map { "${it.trimEnd(':')}:${BasePlugin.CLEAN_TASK_NAME}" }

tasks.withType<SpotlessTask>().configureEach {
    mustRunAfter(cleanTasks)
}
