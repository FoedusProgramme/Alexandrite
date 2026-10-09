plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
    id("alexandrite.poko")
    `java-test-fixtures`
}

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))

    testFixturesApi(project(":libraries:testkit"))
    testRuntimeOnly(libs.logback.classic)
}
