plugins {
    id("alexandrite.kotlin-library")
}

description = "Internal helpers shared by the Alexandrite modules"

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
}
