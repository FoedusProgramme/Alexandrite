plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
}

dependencies {
    api(project(":libraries:plugin-sdk"))
    api(project(":libraries:runtime"))
    implementation(project(":libraries:internal"))

    testImplementation(libs.kctfork.core)
    testRuntimeOnly(libs.logback.classic)
}
