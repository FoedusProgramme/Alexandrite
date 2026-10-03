package org.foedusprogramme.alexandrite.ksp

internal object Messages {
    fun missingModule(): String = "The KSP option '$MODULE_OPTION' is not set. Apply the alexandrite.ksp convention " +
        "plugin, or set the module name in the build file: ksp { arg(\"$MODULE_OPTION\", \"my-plugin\") }."

    fun malformedModule(module: String): String =
        "The KSP option '$MODULE_OPTION' is '$module'. Use lowercase letters and digits, starting with a letter, " +
            "with single hyphens between words, such as \"my-plugin\"."

    fun reservedModule(module: String): String =
        "Module name '$module' starts with '$RESERVED_PREFIX', which is reserved for the built-in modules of " +
            "Alexandrite. Choose another name, such as \"my-plugin\"."

    fun missingConfigRoot(module: String): String = "Built-in module '$module' needs the KSP option " +
        "'$CONFIG_ROOT_OPTION', which the alexandrite.ksp convention plugin sets from the layout."

    fun thirdPartyRoot(module: String, configRoot: String): String =
        "The KSP option '$CONFIG_ROOT_OPTION' is '$configRoot', but the config root of module '$module' is always " +
            "'$THIRD_PARTY_ROOT.$module'. Remove the option."

    fun malformedPackage(packageName: String): String =
        "The KSP option '$PACKAGE_OPTION' is '$packageName'. Use a package name of dot-separated identifiers, " +
            "such as \"com.example.myplugin\"."

    fun missingPackage(module: String): String =
        "The index of module '$module' has no package: the KSP option '$PACKAGE_OPTION' is not set and the " +
            "module's annotated classes share no package. Set it in the build file: " +
            "ksp { arg(\"$PACKAGE_OPTION\", \"com.example.${module.replace("-", "")}\") }."

    fun interfaceComponent(name: String, simpleName: String): String =
        "$name is an interface, so the container cannot create it. " +
            "Annotate a class that implements it instead, with @Binds($simpleName::class)."

    fun objectComponent(name: String): String =
        "$name is an object, but the container creates every component itself. Make it a class."

    fun notAClass(name: String, kind: String): String =
        "$name is $kind, so the container cannot create it. Only a class can be a component."

    fun abstractComponent(name: String): String =
        "$name is abstract, so the container cannot create it. Annotate a concrete subclass instead."

    fun innerComponent(name: String): String =
        "$name is an inner class, so it needs an outer instance the container does not have. " +
            "Remove the inner modifier."

    fun localClass(name: String): String =
        "$name is local, so the generated index cannot reach it. Declare it at the top level or inside a class."

    fun hiddenClass(name: String, hidden: String, visibility: String): String {
        val where = if (hidden == name) "is $visibility" else "is inside $visibility class $hidden"
        return "$name $where, so the generated index cannot reach it. Make it public or internal."
    }

    fun genericClass(name: String): String = "$name has type parameters, so it has no single key. " +
        "Remove them, or annotate a subclass with concrete type arguments."

    fun twoScopes(name: String): String =
        "$name is annotated both @Singleton and @ChannelInstanceScoped. Keep only the one for the scope it needs."

    fun sectionComponent(name: String): String =
        "$name is a @ConfigSection, so it cannot also be a component. Inject it into a component instead."

    fun severalInject(name: String): String =
        "$name has several @Inject constructors. Keep @Inject on the one the container calls."

    fun hiddenInject(name: String): String = "The @Inject constructor of $name is private or protected, " +
        "so the generated index cannot call it. Make it public or internal."

    fun severalConstructors(name: String): String = "$name has several constructors and none is annotated " +
        "@Inject. Annotate the one the container calls with @Inject."

    fun noConstructor(name: String): String = "$name has no public or internal constructor, " +
        "so the generated index cannot create it. Make one public or internal."

    fun defaultValue(parameter: String, owner: String): String =
        "Parameter '$parameter' of $owner has a default value. Defaults are not allowed on injected " +
            "parameters: the container passes every parameter, so the value would be silently ignored. " +
            "Remove the default, and inject T? if the dependency may be missing."

    fun vararg(parameter: String, owner: String): String =
        "Parameter '$parameter' of $owner is a vararg. Inject List<T> to get every contribution to T."

    fun functionType(parameter: String, owner: String, type: String): String =
        "Parameter '$parameter' of $owner has function type $type. The only function type injected is " +
            "() -> T, which resolves T on every call. Inject () -> T or Lazy<T>, or an interface with the function."

    fun projectedArgument(parameter: String, owner: String, type: String): String =
        "Parameter '$parameter' of $owner has type $type, whose type argument is nullable or a star projection. " +
            "Keys are non-null types: inject List<T>, Lazy<T> or () -> T with a non-null T."

    fun unqualified(parameter: String, owner: String, type: String): String =
        "Parameter '$parameter' of $owner has type $type, which is too general to inject without a qualifier. " +
            "Annotate it with @Named(\"…\"), or inject a @ConfigSection class that holds the value."

    fun namedModuleLocal(parameter: String, owner: String, type: String): String =
        "Parameter '$parameter' of $owner has type $type, which every module gets its own instance of under the " +
            "module's name, so it cannot be @Named. Remove @Named."

    fun notSupertype(name: String, annotation: String, bound: String): String =
        "$name lists $bound in $annotation, but $bound is not a supertype of $name. " +
            "Implement $bound, or remove it from $annotation."

    fun unknownArguments(name: String, annotation: String, bound: String): String =
        "$name lists $bound in $annotation, but inherits it through a generic class, so its type arguments are " +
            "unknown. Implement $bound with concrete type arguments in $name itself."

    fun notReturnSupertype(name: String, annotation: String, bound: String): String =
        "$name lists $bound in $annotation, but $bound is not a supertype of its return type. " +
            "Return a subtype of $bound, or remove it from $annotation."

    fun unknownReturnArguments(name: String, annotation: String, bound: String): String =
        "$name lists $bound in $annotation, but its return type inherits it through a generic class, so its type " +
            "arguments are unknown. Return a type that implements $bound with concrete type arguments."

    fun duplicateKey(key: String, first: String, second: String): String =
        "$key is bound twice in this module, by $first and by $second. " +
            "Remove one of them, or tell them apart with @Named."

    fun uncontributed(name: String, spi: String, contributes: Boolean): String =
        "$name implements $spi, but does not contribute to it, so it is never used as one. " +
            "${addContribute(spi, contributes)}."

    fun uncontributedProvider(name: String, type: String, spi: String, contributes: Boolean): String =
        "$name returns $type, which implements $spi, but does not contribute it, so it is never used as one. " +
            "${addContribute(spi, contributes)}."

    fun providedSpi(name: String, spi: String): String =
        "$name returns $spi itself, but $spi is an SPI whose implementations are contributed, not bound. " +
            "Return the implementing type and annotate the function with @Contribute(${simple(spi)}::class)."

    fun boundSpi(name: String, spi: String): String =
        "$name lists $spi in @Binds, but $spi is an SPI whose implementations are contributed, not bound. " +
            "Use @Contribute(${simple(spi)}::class) instead."

    fun contributedSubtype(name: String, bound: String, spis: List<String>): String =
        "$name lists $bound in @Contribute, but $bound is a subtype of ${spis.joinToString(" and ")}, and only " +
            "contributions to the SPI itself are collected. " +
            "Use @Contribute(${spis.joinToString { "${simple(it)}::class" }}) instead."

    fun unannotatedDependency(parameter: String, owner: String, type: String): String =
        "Parameter '$parameter' of $owner has type $type, a class of this module that is not a component, a " +
            "config section or returned by a @Provides function, so the container cannot create it. " +
            "Annotate $type with @Singleton, or provide it with a @Provides function."

    fun scopeBreak(parameter: String, owner: String, targets: List<String>): String =
        "Singleton $owner depends on channel-instance-scoped ${targets.joinToString(" and ")} through parameter " +
            "'$parameter'. Annotate $owner with @ChannelInstanceScoped or drop the dependency."

    fun notProvides(name: String, annotations: List<String>): String {
        val labels = annotations.joinToString(" and ") { "@" + it.substringAfterLast('.') }
        return "$name is annotated $labels but not @Provides, so nothing binds what it returns. " +
            "Annotate it with @Provides, or remove $labels."
    }

    fun localProvider(name: String): String = "Provider $name is local, so the generated index cannot reach it. " +
        "Declare it at the top level or inside an object."

    fun providerInClass(name: String, owner: String): String =
        "Provider $name is declared in $owner, which is not an object, so the container has no instance to call " +
            "it on. Move it to the top level or into an object."

    fun hiddenProvider(name: String, hidden: String?, visibility: String): String {
        val where = if (hidden == null) "is $visibility" else "is inside $visibility object $hidden"
        return "Provider $name $where, so the generated index cannot call it. Make it public or internal."
    }

    fun suspendProvider(name: String): String =
        "Provider $name is a suspend function, but the container creates instances without suspending. " +
            "Remove suspend, and do suspending work in Startable.start()."

    fun extensionProvider(name: String): String =
        "Provider $name is an extension function, so the container has no receiver to call it on. " +
            "Take the receiver as a parameter instead."

    fun genericProvider(name: String): String = "Provider $name has type parameters, so it has no single key. " +
        "Remove them, or declare one provider per concrete type."

    fun nullableProvider(name: String, type: String): String =
        "Provider $name returns $type, but a binding always has an instance. " +
            "Return a non-null type, and inject it as optional where it may be missing."

    fun unitProvider(name: String): String =
        "Provider $name returns Unit, so there is nothing to bind. Return the instance to bind."

    fun notSerializable(name: String): String = "@ConfigSection class $name is not annotated @Serializable. " +
        "Annotate it with @kotlinx.serialization.Serializable so its section can be decoded."

    fun malformedPath(name: String, path: String): String = "The @ConfigSection path '$path' of $name is malformed. " +
        "Use \"\" for the module's config root, or dot-separated names that start with a letter and hold only " +
        "letters, digits, '-' and '_', such as \"cache.disk\"."

    fun reservedPath(name: String, path: String): String =
        "The @ConfigSection path '$path' of $name lies under '$ENABLED', which switches the module on and off. " +
            "Choose another path."

    fun duplicatePath(path: String, first: String, second: String): String =
        "Config section '$path' is declared by both $first and $second. Give each class its own path."

    private fun addContribute(spi: String, contributes: Boolean): String {
        val type = simple(spi)
        return if (contributes) "Add $type::class to its @Contribute" else "Annotate it with @Contribute($type::class)"
    }

    private fun simple(name: String): String = name.substringAfterLast('.')
}
