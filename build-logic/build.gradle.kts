plugins {
    `kotlin-dsl`
    alias(libs.plugins.spotless)
}

kotlin {
    jvmToolchain(21)
}

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
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
    implementation(libs.spotless.gradle.plugin)
    implementation("org.foedusprogramme.alexandrite.buildlogic:build-logic-settings")
}
