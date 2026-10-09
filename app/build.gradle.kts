plugins {
    id("alexandrite.kotlin-application")
    id("alexandrite.kotlin-serialization")
}

dependencies {
    implementation(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:runtime"))
    implementation(project(":libraries:agent"))
    implementation(project(":libraries:tools"))
    implementation(project(":libraries:channels:telegram"))
    implementation(project(":libraries:providers:openai-compatible"))
    implementation(project(":libraries:providers:deepseek"))
    implementation(project(":libraries:providers:openrouter"))
    implementation(project(":libraries:providers:lmstudio"))
    implementation(project(":libraries:providers:anthropic"))
    implementation(project(":libraries:stores:sqlite"))
    runtimeOnly(libs.logback.classic)

    testImplementation(project(":examples:notes"))
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
