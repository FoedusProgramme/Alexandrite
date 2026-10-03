plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
    id("alexandrite.poko")
    id("alexandrite.built-in-list")
}

dependencies {
    api(project(":libraries:plugin-sdk"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.logback.classic)
}
