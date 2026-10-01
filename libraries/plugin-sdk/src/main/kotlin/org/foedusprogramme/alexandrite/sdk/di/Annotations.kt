package org.foedusprogramme.alexandrite.sdk.di

import kotlin.reflect.KClass

/** One instance per root container, the default scope of a component. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Singleton

/** One instance per channel container. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class ChannelScoped

/** The constructor the container calls when a class has several. */
@Target(AnnotationTarget.CONSTRUCTOR)
@Retention(AnnotationRetention.BINARY)
public annotation class Inject

/** The qualifier of the key a parameter injects or a class is bound under. */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Named(val value: String)

/** Also binds the instance as the single binding of each of [types]. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Binds(vararg val types: KClass<*>)

/** Adds the instance as a multibinding contribution to each of [types]. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Contribute(vararg val types: KClass<*>)
