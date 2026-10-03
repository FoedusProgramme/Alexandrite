import com.diffplug.gradle.spotless.SpotlessTask

plugins {
    base
    alias(libs.plugins.spotless)
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

val cleanTasks = allprojects.map { project -> project.tasks.named { it == BasePlugin.CLEAN_TASK_NAME } }

allprojects {
    tasks.withType<SpotlessTask>().configureEach {
        mustRunAfter(cleanTasks)
    }
}
