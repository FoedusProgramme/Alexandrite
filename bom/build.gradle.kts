import org.unitedsoftware.alexandrite.buildlogic.AlexandriteLayout
import org.unitedsoftware.alexandrite.buildlogic.Layer

plugins {
    `java-platform`
    id("alexandrite.publish")
}

description = "Aligns the versions of the Alexandrite modules a third-party plugin builds against"

dependencies {
    constraints {
        val modules = rootProject.subprojects.mapNotNull { AlexandriteLayout.moduleAt(it.path) }
        for (published in modules.filter { it.published && it.layer != Layer.BOM }) {
            api(project(published.path))
        }
        api("org.unitedsoftware.alexandrite:alexandrite-gradle-plugin:$version")
    }
}
