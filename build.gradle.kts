plugins {
    base
    id("alexandrite.spotless")
}

spotless {
    kotlinGradle {
        ktlint(libs.versions.ktlint.get())
    }
}

val buildLogic = listOf("build-logic-settings", "build-logic", "build-gradle-plugin").map(gradle::includedBuild)

tasks.check {
    dependsOn(buildLogic.map { it.task(":check") })
}

tasks.spotlessCheck {
    dependsOn(buildLogic.map { it.task(":spotlessCheck") })
}

tasks.spotlessApply {
    dependsOn(buildLogic.map { it.task(":spotlessApply") })
}

for (name in listOf("publishToMavenLocal", "publishToMavenCentral", "publishAndReleaseToMavenCentral")) {
    tasks.register(name) {
        group = "publishing"
        dependsOn(gradle.includedBuild("build-gradle-plugin").task(":$name"))
    }
}
