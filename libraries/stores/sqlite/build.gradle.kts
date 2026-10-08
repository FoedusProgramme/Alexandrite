plugins {
    id("alexandrite.kotlin-library")
}

dependencies {
    implementation(project(":libraries:plugin-sdk"))
    implementation(libs.sqlite.jdbc)

    testImplementation(project(":libraries:testkit"))
    testRuntimeOnly(libs.logback.classic)
}
