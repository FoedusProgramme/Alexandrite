plugins {
    id("alexandrite.kotlin-library")
}

dependencies {
    implementation(libs.ksp.api)

    testImplementation(project(":libraries:plugin-sdk"))
    testImplementation(libs.kctfork.core)
    testImplementation(libs.kctfork.ksp)
    testImplementation(libs.kotlin.serialization.compiler.plugin.embeddable)
}
