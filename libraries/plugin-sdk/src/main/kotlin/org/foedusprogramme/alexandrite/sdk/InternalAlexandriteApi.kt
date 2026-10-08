package org.foedusprogramme.alexandrite.sdk

/** Marks SDK API that only Alexandrite's own modules and generated plugin indexes may use. */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal Alexandrite API: only Alexandrite's own modules and generated plugin indexes may use it.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
public annotation class InternalAlexandriteApi
