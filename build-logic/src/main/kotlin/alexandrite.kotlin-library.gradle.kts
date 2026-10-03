import org.foedusprogramme.alexandrite.buildlogic.AlexandriteLayout

plugins {
    `java-library`
    kotlin("jvm")
    id("com.diffplug.spotless")
}

group = "org.foedusprogramme.alexandrite"
version = "0.1.0-SNAPSHOT"

val libs = the<VersionCatalogsExtension>().named("libs")

val module = AlexandriteLayout.moduleAt(path) ?: throw GradleException(AlexandriteLayout.noLocationMessage(path))

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

if (module.layer in AlexandriteLayout.INDEXED_LAYERS) {
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
