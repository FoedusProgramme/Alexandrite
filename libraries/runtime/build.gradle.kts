plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
    id("alexandrite.built-in-list")
}

dependencies {
    api(project(":libraries:plugin-sdk"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.logback.classic)
}
