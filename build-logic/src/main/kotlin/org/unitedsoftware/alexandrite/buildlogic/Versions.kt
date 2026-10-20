package org.unitedsoftware.alexandrite.buildlogic

import org.gradle.api.Project

/** The version of every module, set with `-Palexandrite.version` */
val Project.alexandriteVersion: String
    get() = providers.gradleProperty("alexandrite.version").getOrElse("0.1.0-SNAPSHOT")
