import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
    `kotlin-dsl`
    alias(libs.plugins.spotless)
    alias(libs.plugins.maven.publish)
    signing
}

group = "org.unitedsoftware.alexandrite"
version = providers.gradleProperty("alexandrite.version").getOrElse("0.1.0-SNAPSHOT")
description = "The Gradle plugin that builds a third-party Alexandrite plugin"

kotlin {
    jvmToolchain(21)
    explicitApi()
}

gradlePlugin {
    plugins {
        create("alexandrite") {
            id = "org.unitedsoftware.alexandrite.plugin"
            implementationClass = "org.unitedsoftware.alexandrite.gradle.AlexandritePlugin"
            displayName = "Alexandrite plugin"
            description = project.description
        }
    }
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val versionSource = tasks.register("versionSource") {
    val pluginVersion = version.toString()
    val output = layout.buildDirectory.dir("generated/version")
    inputs.property("version", pluginVersion)
    outputs.dir(output)
    doLast {
        val file = output.get().file("org/unitedsoftware/alexandrite/gradle/Version.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package org.unitedsoftware.alexandrite.gradle\n\n" +
                "internal const val ALEXANDRITE_VERSION: String = \"$pluginVersion\"\n",
        )
    }
}

kotlin.sourceSets.main {
    kotlin.srcDir(versionSource)
}

tasks.test {
    useJUnitPlatform()
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.versions.ktlint.get()).setEditorConfigPath("../.editorconfig")
    }
    kotlinGradle {
        ktlint(libs.versions.ktlint.get()).setEditorConfigPath("../.editorconfig")
    }
}

mavenPublishing {
    configure(GradlePlugin(JavadocJar.Empty(), SourcesJar.Sources()))
    publishToMavenCentral(automaticRelease = false)
    signAllPublications()
    coordinates(artifactId = "alexandrite-gradle-plugin")
    pom {
        name = "alexandrite-gradle-plugin"
        description = project.description
        url = "https://alexandrite.unitedsoftware.org"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "unitedsoftware"
                name = "United Software"
                email = "contact@unitedsoftware.org"
                url = "https://unitedsoftware.org"
                organization = "United Software"
                organizationUrl = "https://unitedsoftware.org"
            }
        }
        scm {
            url = "https://github.com/FoedusProgramme/alexandrite"
            connection = "scm:git:https://github.com/FoedusProgramme/alexandrite.git"
            developerConnection = "scm:git:ssh://git@github.com/FoedusProgramme/alexandrite.git"
        }
    }
}

signing {
    isRequired = gradle.startParameter.taskNames.any { "MavenCentral" in it } ||
        providers.gradleProperty("signingInMemoryKey").isPresent
}
