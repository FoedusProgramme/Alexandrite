plugins {
    `kotlin-dsl`
    alias(libs.plugins.spotless)
}

kotlin {
    jvmToolchain(21)
}

group = "org.foedusprogramme.alexandrite.buildlogic"
version = "1.0"

spotless {
    kotlin {
        target("src/**/*.kt", "src/**/*.kts")
        ktlint(libs.versions.ktlint.get()).setEditorConfigPath("../.editorconfig")
    }
    kotlinGradle {
        ktlint(libs.versions.ktlint.get()).setEditorConfigPath("../.editorconfig")
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    inputs.files(
        fileTree(rootDir.parentFile) {
            include("libraries/**/src/main/kotlin/**", "app/src/main/kotlin/**")
            exclude("**/build/**")
        },
    ).withPropertyName("moduleSources").withPathSensitivity(PathSensitivity.RELATIVE)
}
