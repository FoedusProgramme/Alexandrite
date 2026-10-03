plugins {
    id("alexandrite.kotlin-library")
}

dependencies {
    implementation(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))
}
