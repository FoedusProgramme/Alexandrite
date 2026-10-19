plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
}

dependencies {
    implementation(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))
    implementation(project(":libraries:automation-common"))

    testImplementation(project(":libraries:testkit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.logback.classic)
}

kotlin {
    compilerOptions {
        optIn.add("org.unitedsoftware.alexandrite.automation.common.InternalAutomationApi")
    }
}
