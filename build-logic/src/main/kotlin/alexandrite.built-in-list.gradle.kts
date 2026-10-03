import org.foedusprogramme.alexandrite.buildlogic.AlexandriteLayout
import org.foedusprogramme.alexandrite.buildlogic.BuildFileScan
import org.foedusprogramme.alexandrite.buildlogic.BuiltInList
import org.foedusprogramme.alexandrite.buildlogic.GenerateBuiltInList
import org.foedusprogramme.alexandrite.buildlogic.Layer

plugins {
    kotlin("jvm")
}

if (AlexandriteLayout.moduleAt(path)?.layer != Layer.RUNTIME) {
    throw GradleException(
        "Project '$path' applies 'alexandrite.built-in-list', which generates the runtime's built-in plugin list. " +
            "Only the ${Layer.RUNTIME} module may apply it.",
    )
}

val generateBuiltInList = tasks.register<GenerateBuiltInList>("generateBuiltInList") {
    kotlinSource = providers.of(BuildFileScan::class.java) { parameters.rootDirectory = layout.settingsDirectory }
        .map { BuiltInList.source(AlexandriteLayout.discover(it)) }
    outputDirectory = layout.buildDirectory.dir("generated/builtInList")
}

kotlin.sourceSets.main {
    kotlin.srcDir(generateBuiltInList)
}
