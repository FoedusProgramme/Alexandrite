plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
}

dependencies {
    implementation(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))

    // The client and the server of the Websocket transports, of which the JDK offers a client only.
    implementation(libs.java.websocket)

    testImplementation(project(":libraries:testkit"))
    testRuntimeOnly(libs.logback.classic)
}
