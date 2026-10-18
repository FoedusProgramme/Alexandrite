plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
    id("alexandrite.poko")
}

dependencies {
    implementation(project(":libraries:plugin-sdk"))
    api(project(":libraries:automation-common"))

    testImplementation(project(":libraries:testkit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.logback.classic)
}

kotlin {
    compilerOptions {
        optIn.add("org.foedusprogramme.alexandrite.automation.common.InternalAutomationApi")
    }
}
