plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
}

kotlin {
    explicitApi()
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
}
