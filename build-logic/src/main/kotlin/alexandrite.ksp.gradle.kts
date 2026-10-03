import org.foedusprogramme.alexandrite.buildlogic.AlexandriteLayout

plugins {
    id("com.google.devtools.ksp")
}

val module = AlexandriteLayout.moduleAt(path) ?: throw GradleException(AlexandriteLayout.noLocationMessage(path))
val configRoot = module.configRoot ?: throw GradleException(
    "Project '$path' (${module.layer}) has no config root, so its PluginIndex cannot be generated. " +
        "Give its location a config root in ${AlexandriteLayout.LAYOUT_LOCATION}.",
)

dependencies {
    "ksp"(project(":build-ksp-plugin"))
}

ksp {
    arg("alexandrite.plugin", module.moduleName)
    arg("alexandrite.version", version.toString())
    module.packageName?.let { arg("alexandrite.package", it) }
    module.indexClass?.let { arg("alexandrite.indexClass", it) }
    if (module.builtIn) {
        arg("alexandrite.configRoot", configRoot)
        arg("alexandrite.builtIn", "true")
    }
}
