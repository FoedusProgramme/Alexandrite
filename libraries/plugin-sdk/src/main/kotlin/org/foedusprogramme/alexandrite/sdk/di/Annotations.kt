package org.foedusprogramme.alexandrite.sdk.di

import kotlin.reflect.KClass

/** One instance per root container, the default scope of a component. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Singleton

/** One instance per channel instance. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class ChannelInstanceScoped

/** The constructor the container calls when a class has several. */
@Target(AnnotationTarget.CONSTRUCTOR)
@Retention(AnnotationRetention.BINARY)
public annotation class Inject

/** The qualifier of the key a parameter injects or a component is bound under. */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Named(val value: String)

/** Also binds the instance as the single binding of each of [types]. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Binds(vararg val types: KClass<*>)

/** Adds the instance as a multibinding contribution to each of [types]. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Contribute(vararg val types: KClass<*>)

/** Binds what the function returns under its return type. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Provides

/** Marks an SPI whose implementations are contributed. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class ContributedSpi

/** Marks a service type each module gets its own instance of, qualified by the module name. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class ModuleLocal
