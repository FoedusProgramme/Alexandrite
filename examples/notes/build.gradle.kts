plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
    id("org.unitedsoftware.alexandrite.plugin")
}

alexandrite {
    pluginId = "notes"
}

dependencies {
    implementation(libs.sqlite.jdbc)

    testRuntimeOnly(libs.logback.classic)
}
