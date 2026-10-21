plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
}

description = "The DeepSeek model provider of Alexandrite"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:provider-common"))

    testImplementation(project(":libraries:testkit"))
    testImplementation(testFixtures(project(":libraries:provider-common")))
    testRuntimeOnly(libs.logback.classic)
}
