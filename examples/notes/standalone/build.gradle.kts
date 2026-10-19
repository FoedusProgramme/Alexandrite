plugins {
    id("org.unitedsoftware.alexandrite.plugin")
    alias(libs.plugins.kotlin.serialization)
}

alexandrite {
    pluginId = "notes"
}

kotlin.sourceSets {
    main { kotlin.setSrcDirs(listOf("../src/main/kotlin")) }
    test { kotlin.setSrcDirs(listOf("../src/test/kotlin")) }
}

dependencies {
    implementation(libs.sqlite.jdbc)

    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.logback.classic)
}

tasks.test {
    useJUnitPlatform()
}
