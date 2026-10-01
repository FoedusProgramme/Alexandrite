import org.foedusprogramme.alexandrite.buildlogic.AlexandriteLayout

plugins {
    id("com.google.devtools.ksp")
}

val module = AlexandriteLayout.moduleAt(path) ?: throw GradleException(AlexandriteLayout.noLocationMessage(path))
val configRoot = module.configRoot ?: throw GradleException(
    "Project '$path' (${module.layer}) has no config root, so its ModuleIndex cannot be generated. " +
        "Give its location a config root in ${AlexandriteLayout.LAYOUT_LOCATION}.",
)

dependencies {
    "ksp"(project(":build-ksp-plugin"))
}

ksp {
    arg("alexandrite.module", module.jarName)
    arg("alexandrite.configRoot", configRoot)
}
