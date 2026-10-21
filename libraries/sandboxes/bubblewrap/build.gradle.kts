plugins {
    id("alexandrite.kotlin-library")
}

description = "The bubblewrap sandbox that Alexandrite runs commands and MCP servers in on Linux"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))

    testImplementation(project(":libraries:testkit"))
}

tasks.register<Exec>("linuxTest") {
    description = "Runs the tests that need Linux and bubblewrap in a Debian container (needs Docker)."
    group = "verification"
    commandLine("sh", file("linux-test.sh").absolutePath)
}
