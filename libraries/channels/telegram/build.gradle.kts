plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))

    testFixturesApi(libs.kotlinx.serialization.json)
    testFixturesImplementation(project(":libraries:plugin-sdk"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.logback.classic)
}
