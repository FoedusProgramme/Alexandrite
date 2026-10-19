plugins {
    id("alexandrite.kotlin-library")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
}
