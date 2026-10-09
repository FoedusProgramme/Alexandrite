import org.foedusprogramme.alexandrite.buildlogic.AlexandriteLayout
import org.foedusprogramme.alexandrite.buildlogic.Layer

plugins {
    `java-library`
    kotlin("jvm")
    id("alexandrite.spotless")
}

val module = AlexandriteLayout.moduleAt(path) ?: throw GradleException(AlexandriteLayout.noLocationMessage(path))

group = module.group
version = "0.1.0-SNAPSHOT"

val libs = the<VersionCatalogsExtension>().named("libs")

base {
    archivesName = module.jarName
}

// Fail as soon as a forbidden project dependency is declared
configurations.configureEach {
    val configurationName = name
    dependencies.withType<ProjectDependency>().configureEach {
        AlexandriteLayout.dependencyViolation(module, dependencyPath = this.path, configurationName)
            ?.let { throw GradleException(it) }
    }
}

kotlin {
    jvmToolchain(21)
    explicitApi()
    compilerOptions {
        allWarningsAsErrors = true
    }
}

if (module.layer !in AlexandriteLayout.THIRD_PARTY_LAYERS) {
    // The SDK's main code opts in per declaration.
    val mainOptsIn = Layer.SDK in AlexandriteLayout.LAYER_DEPENDENCIES.getValue(module.layer)
    val testOptsIn = module.layer == Layer.SDK || mainOptsIn ||
        Layer.SDK in AlexandriteLayout.TEST_LAYER_DEPENDENCIES[module.layer].orEmpty()
    kotlin.target.compilations.configureEach {
        if (if (name == "test") testOptsIn else mainOptsIn) {
            compileTaskProvider.configure {
                compilerOptions.optIn.add("org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi")
            }
        }
    }
}

if (module.builtIn && module.layer in AlexandriteLayout.INDEXED_LAYERS) {
    apply(plugin = "alexandrite.ksp")
}

dependencies {
    testImplementation(libs.findLibrary("kotlin-test").get())
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

spotless {
    val ktlintVersion = libs.findVersion("ktlint").get().requiredVersion
    kotlin {
        target("src/**/*.kt")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        ktlint(ktlintVersion)
    }
}
