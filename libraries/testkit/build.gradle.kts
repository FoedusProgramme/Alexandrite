plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
}

dependencies {
    api(project(":libraries:plugin-sdk"))
    api(project(":libraries:runtime"))

    testImplementation(libs.kctfork.core)
    testRuntimeOnly(libs.logback.classic)
}
