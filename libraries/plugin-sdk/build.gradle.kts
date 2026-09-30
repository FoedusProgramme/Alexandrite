plugins {
    id("alexandrite.kotlin-library")
}

kotlin {
    explicitApi()
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}
