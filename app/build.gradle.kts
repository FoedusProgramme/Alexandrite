plugins {
    id("alexandrite.kotlin-application")
    id("alexandrite.kotlin-serialization")
}

dependencies {
    implementation(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:runtime"))
    implementation(project(":libraries:agent"))
    implementation(project(":libraries:tools"))
    implementation(project(":libraries:mcp"))
    implementation(project(":libraries:commands"))
    implementation(project(":libraries:channels:telegram"))
    implementation(project(":libraries:channel-common"))
    implementation(project(":libraries:providers:openai-compatible"))
    implementation(project(":libraries:providers:openai-responses"))
    implementation(project(":libraries:providers:deepseek"))
    implementation(project(":libraries:providers:openrouter"))
    implementation(project(":libraries:providers:lmstudio"))
    implementation(project(":libraries:providers:anthropic-compatible"))
    implementation(project(":libraries:stores:sqlite"))
    implementation(project(":libraries:automations:heartbeat"))
    implementation(project(":libraries:automations:schedule"))
    runtimeOnly(libs.logback.classic)

    testImplementation(project(":libraries:testkit"))
    testImplementation(project(":examples:notes"))
    testImplementation(testFixtures(project(":libraries:channels:telegram")))
    testImplementation(testFixtures(project(":libraries:mcp")))
    testImplementation(project(":libraries:automation-common"))
    testImplementation(libs.logback.classic)
}

application {
    mainClass = "org.foedusprogramme.alexandrite.app.MainKt"
}

val versionResource = tasks.register<WriteProperties>("versionResource") {
    destinationFile = layout.buildDirectory.file("generated/version/alexandrite-version.properties")
    property("version", project.version.toString())
}

sourceSets.main {
    resources.srcDir(versionResource.map { it.destinationFile.get().asFile.parentFile })
}

tasks.test {
    inputs.file("src/dist/config/alexandrite.example.json")
        .withPropertyName("exampleConfig")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
