plugins {
    id("alexandrite.kotlin-library")
}

description = "The SQLite store of Alexandrite"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))
    implementation(libs.sqlite.jdbc)

    testImplementation(project(":libraries:testkit"))
    testRuntimeOnly(libs.logback.classic)
}
