plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
    id("alexandrite.poko")
    id("alexandrite.built-in-list")
}

description = "The embeddable Alexandrite engine: plugin loading, configuration, lifecycle and hooks"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.logback.classic)
}
