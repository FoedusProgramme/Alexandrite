plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
    id("alexandrite.poko")
}

description = "The Alexandrite plugin SDK: the annotations, contracts and types a plugin compiles against"

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kctfork.core)
}
