plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.poko")
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    compilerOptions {
        optIn.add("org.foedusprogramme.alexandrite.automation.common.InternalAutomationApi")
    }
}
