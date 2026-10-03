import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
    id("alexandrite.kotlin-library")
    application
}

kotlin {
    explicitApi = ExplicitApiMode.Disabled
}

application {
    applicationName = base.archivesName.get()
}
