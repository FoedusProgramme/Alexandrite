plugins {
    id("alexandrite.kotlin-application")
}

dependencies {
    implementation(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:common"))
    implementation(project(":libraries:runtime"))
    implementation(project(":libraries:agent"))
    implementation(project(":libraries:tools"))
    implementation(project(":libraries:channels:telegram"))
    implementation(project(":libraries:providers:openai-compatible"))
    implementation(project(":libraries:providers:anthropic"))
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
}

application {
    mainClass = "org.foedusprogramme.alexandrite.app.MainKt"
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("alexandrite-version.properties") {
        expand("version" to version)
    }
}
