plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(21)
}

group = "org.foedusprogramme.alexandrite.buildlogic"
version = "1.0"

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
