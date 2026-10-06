plugins {
    id("alexandrite.kotlin-library")
}

dependencies {
    api(project(":libraries:plugin-sdk"))
    api(project(":libraries:runtime"))

    testRuntimeOnly(libs.logback.classic)
}
