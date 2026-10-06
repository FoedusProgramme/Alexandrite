plugins {
    base
    id("alexandrite.spotless")
}

spotless {
    kotlinGradle {
        ktlint(libs.versions.ktlint.get())
    }
}

val buildLogic = listOf("build-logic-settings", "build-logic").map(gradle::includedBuild)

tasks.check {
    dependsOn(buildLogic.map { it.task(":check") })
}

tasks.spotlessCheck {
    dependsOn(buildLogic.map { it.task(":spotlessCheck") })
}

tasks.spotlessApply {
    dependsOn(buildLogic.map { it.task(":spotlessApply") })
}
