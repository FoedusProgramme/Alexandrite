plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation("org.foedusprogramme.alexandrite.buildlogic:build-logic-settings")
}
