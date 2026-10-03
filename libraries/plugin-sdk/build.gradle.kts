plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
    id("alexandrite.poko")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
}
