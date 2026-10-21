plugins {
    id("alexandrite.kotlin-library")
}

description = "The Seatbelt sandbox that Alexandrite runs commands and MCP servers in on macOS"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))

    testImplementation(project(":libraries:testkit"))
}
