plugins {
    base
}

tasks.check {
    dependsOn(gradle.includedBuild("build-logic-settings").task(":check"))
}
