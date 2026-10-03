plugins {
    id("alexandrite.kotlin-library")
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":libraries:plugin-sdk"))
}
