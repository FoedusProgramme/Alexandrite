plugins {
    id("alexandrite.kotlin-library")
    application
}

application {
    applicationName = base.archivesName.get()
}
