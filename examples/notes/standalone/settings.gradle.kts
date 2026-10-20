// Builds the example from the artifacts in Maven local: run with `-Palexandrite.version=<their version>`

pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("org.unitedsoftware.alexandrite.plugin") version providers.gradleProperty("alexandrite.version").get()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "notes"
