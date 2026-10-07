plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
    id("com.google.devtools.ksp")
}

dependencies {
    compileOnly(project(":libraries:plugin-sdk"))
    implementation(libs.sqlite.jdbc)
    ksp(project(":build-ksp-plugin"))

    testImplementation(project(":libraries:testkit"))
    testRuntimeOnly(libs.logback.classic)
}

ksp {
    arg("alexandrite.plugin", "notes")
    arg("alexandrite.version", version.toString())
}
