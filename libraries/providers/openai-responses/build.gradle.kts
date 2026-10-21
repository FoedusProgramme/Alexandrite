plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
}

description = "The Alexandrite model provider for the OpenAI Responses API"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:provider-common"))

    testImplementation(project(":libraries:testkit"))
    testImplementation(testFixtures(project(":libraries:provider-common")))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.logback.classic)
}
