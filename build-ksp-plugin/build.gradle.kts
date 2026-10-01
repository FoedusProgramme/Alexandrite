plugins {
    id("alexandrite.kotlin-library")
}

dependencies {
    implementation(libs.ksp.api)

    testImplementation(project(":libraries:plugin-sdk"))
    testImplementation(libs.kctfork.core)
    testImplementation(libs.kctfork.ksp)
    testImplementation(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kotlin.serialization.compiler.plugin.embeddable)
    testRuntimeOnly(libs.ksp.aa.embeddable)
    testRuntimeOnly(libs.ksp.common.deps)
}
