plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
}

dependencies {
    implementation(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))

    // The client of the gateway, of which the JDK serves none.
    implementation(libs.java.websocket)

    testImplementation(project(":libraries:testkit"))
    testImplementation(libs.logback.classic)
}
