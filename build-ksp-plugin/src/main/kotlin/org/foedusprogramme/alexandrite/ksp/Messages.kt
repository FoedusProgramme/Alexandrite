package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.Modifier

internal object Messages {
    fun unknownOptions(options: List<String>, known: List<String>): String {
        val noun = if (options.size == 1) "option" else "options"
        return "Unknown KSP $noun ${options.joinToString { "'$it'" }}. The options of the Alexandrite processor " +
            "are ${known.joinToString()}."
    }

    fun missingPlugin(): String = "The KSP option '$PLUGIN_OPTION' is not set. Apply the alexandrite.ksp convention " +
        "plugin, or set the plugin id in the build file: ksp { arg(\"$PLUGIN_OPTION\", \"my-plugin\") }."

    fun malformedPlugin(id: String): String = "The KSP option '$PLUGIN_OPTION' is '$id'. ${pluginIdRule()}"

    fun missingVersion(): String = "The KSP option '$VERSION_OPTION' is not set. Set it to the plugin's version in " +
        "the build file: ksp { arg(\"$VERSION_OPTION\", project.version.toString()) }."

    fun reservedPlugin(id: String): String =
        "Plugin id '$id' starts with '$RESERVED_PREFIX', which is reserved for the built-in plugins of " +
            "Alexandrite. Choose another id, such as \"my-plugin\"."

    fun builtInThirdParty(id: String): String =
        "The KSP option '$BUILT_IN_OPTION' is 'true', but plugin id '$id' does not start with '$RESERVED_PREFIX', " +
            "as the ids of built-in plugins do. Remove the option."

    fun malformedBuiltIn(value: String): String =
        "The KSP option '$BUILT_IN_OPTION' is '$value'. Use \"true\" or \"false\"."

    fun missingConfigRoot(id: String): String = "Built-in plugin '$id' needs the KSP option " +
        "'$CONFIG_ROOT_OPTION', which the alexandrite.ksp convention plugin sets from the layout."

    fun malformedConfigRoot(configRoot: String): String =
        "The KSP option '$CONFIG_ROOT_OPTION' is '$configRoot'. Use dot-separated names that start with a letter " +
            "and hold only letters, digits, '-' and '_', such as \"channels.telegram\"."

    fun thirdPartyRoot(id: String, configRoot: String): String =
        "The KSP option '$CONFIG_ROOT_OPTION' is '$configRoot', but the config root of plugin '$id' is always " +
            "'$THIRD_PARTY_ROOT.$id'. Remove the option."

    fun malformedPackage(packageName: String): String =
        "The KSP option '$PACKAGE_OPTION' is '$packageName'. Use a package name of dot-separated identifiers, " +
            "such as \"com.example.myplugin\"."

    fun malformedIndexClass(indexClass: String): String =
        "The KSP option '$INDEX_CLASS_OPTION' is '$indexClass'. Use a class name qualified with its package, " +
            "such as \"com.example.myplugin.MyPluginIndex\"."

    fun indexClassOutsidePackage(indexClass: String, packageName: String): String =
        "The KSP option '$INDEX_CLASS_OPTION' is '$indexClass', which is not in package '$packageName' that " +
            "'$PACKAGE_OPTION' names. Remove '$PACKAGE_OPTION', or make the two agree."

    fun missingPackage(id: String): String =
        "The index of plugin '$id' has no package: the KSP option '$PACKAGE_OPTION' is not set and the " +
            "plugin's annotated classes share no package. Set it in the build file: " +
            "ksp { arg(\"$PACKAGE_OPTION\", \"com.example.${id.replace("-", "")}\") }."

    fun hiddenPackage(declaration: String, root: String, packageName: String): String =
        "$declaration is declared in package $packageName, where the generated index refers to names that start " +
            "with '$root', so it would hide them. Rename it, or set the KSP option '$PACKAGE_OPTION' to put the " +
            "index in another package."

    fun missingEntry(id: String): String = "Plugin '$id' has no @Plugin class. Annotate its entry class with " +
        "@Plugin(name = \"…\"): every plugin has exactly one."

    fun severalEntries(id: String, classes: List<String>): String =
        "Plugin '$id' has several @Plugin classes: ${classes.joinToString()}. One Gradle module is one plugin: " +
            "keep @Plugin on one class, or split the module into one module per plugin."

    fun channelInstanceEntry(name: String): String =
        "$name is the @Plugin class, a singleton, so it cannot be @ChannelInstanceScoped. " +
            "Remove @ChannelInstanceScoped."

    fun malformedRequire(name: String, required: String): String =
        "@Plugin class $name requires '$required', which is not a plugin id. ${pluginIdRule()}"

    fun duplicateRequire(name: String, required: String): String =
        "@Plugin class $name lists '$required' twice in requires. Remove one of them."

    fun selfRequire(name: String, id: String): String =
        "@Plugin class $name requires '$id', which is its own plugin. Remove it from requires."

    fun interfaceComponent(name: String, simpleName: String, spi: Boolean): String {
        val annotation = if (spi) "@Contribute" else "@Binds"
        return "$name is an interface, so the container cannot create it. " +
            "Annotate a class that implements it instead, with $annotation($simpleName::class)."
    }

    fun objectComponent(name: String): String = "$name is an object, but the container creates every component " +
        "itself. Make it a class, or bind the object with a @Provides function that returns it."

    fun notAClass(name: String, shape: Shape): String =
        "$name is ${shapeWords(shape)}, so the container cannot create it. Only a class can be a component."

    fun abstractComponent(name: String): String =
        "$name is abstract, so the container cannot create it. Annotate a concrete subclass instead."

    fun innerComponent(name: String): String =
        "$name is an inner class, so it needs an outer instance the container does not have. " +
            "Remove the inner modifier."

    fun unreachableClass(name: String, unreachable: Unreachable): String = when (unreachable) {
        Unreachable.Local ->
            "$name is local, so the generated index cannot reach it. Declare it at the top level or inside a class."

        is Unreachable.Hidden ->
            "$name ${hiddenWhere(unreachable)}, so the generated index cannot reach it. Make it public or internal."
    }

    fun genericClass(name: String): String = "$name has type parameters, so it has no single key. " +
        "Remove them, or annotate a subclass with concrete type arguments."

    fun twoScopes(name: String): String =
        "$name is annotated both @Singleton and @ChannelInstanceScoped. Keep only the one for the scope it needs."

    fun sectionComponent(name: String): String =
        "$name is a @ConfigSection, so it cannot also be a component. Inject it into a component instead."

    fun strayClass(name: String, annotations: List<String>): String {
        val labels = annotations.joinToString(" and ") { annotationLabel(it) }
        return "$name uses $labels but is not annotated @Singleton or @ChannelInstanceScoped, so the container " +
            "never creates it. Annotate it with @Singleton, or remove $labels."
    }

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

    fun nullableWrapper(parameter: String, owner: String, type: String, kind: DependencyKind): String {
        val remedy = when (kind) {
            DependencyKind.ALL ->
                "A multibinding is never null: inject the list without '?', which is empty when " +
                    "nothing contributes."

            else -> "It is never injected as null: drop the '?', or inject T? if the dependency may be missing."
        }
        return "Parameter '$parameter' of $owner has type $type. $remedy"
    }

    fun projectedArgument(parameter: String, owner: String, type: String): String =
        "Parameter '$parameter' of $owner has type $type, whose type argument is nullable or a star projection. " +
            "Keys are non-null types: inject List<T>, Lazy<T> or () -> T with a non-null T."

    fun unqualified(parameter: String, owner: String, type: String): String =
        "Parameter '$parameter' of $owner has type $type, which is too general to inject without a qualifier. " +
            "Annotate it with @Named(\"…\"), or inject a @ConfigSection class that holds the value."

    fun namedPluginLocal(parameter: String, owner: String, type: String): String =
        "Parameter '$parameter' of $owner has type $type, which every plugin gets its own instance of under the " +
            "plugin's id, so it cannot be @Named. Remove @Named."

    fun unexpandedParameter(parameter: String, owner: String, type: String): String =
        "Parameter '$parameter' of $owner has type $type, a type alias that does not expand to a type. " +
            "Use the aliased type itself."

    fun notSupertype(name: String, annotation: String, bound: String, returnType: Boolean): String {
        val subject = if (returnType) "its return type" else name
        return "$name lists $bound in $annotation, but $bound is not a supertype of $subject. " +
            "Make $subject implement $bound, or remove it from $annotation."
    }

    fun unknownArguments(name: String, annotation: String, bound: String, returnType: Boolean): String {
        val subject = if (returnType) "its return type" else name
        return "$name lists $bound in $annotation, but $subject inherits it through a generic class, so its type " +
            "arguments are unknown. Implement $bound with concrete type arguments directly in $subject."
    }

    fun listedTwice(name: String, annotation: String, bound: String): String =
        "$name lists $bound twice in $annotation. Remove one of them."

    fun boundSpi(name: String, spi: String): String =
        "$name lists $spi in @Binds, but $spi is an SPI whose implementations are contributed, not bound. " +
            "Use @Contribute(${simple(spi)}::class) instead."

    fun contributedSubtype(name: String, bound: String, spis: List<String>): String =
        "$name lists $bound in @Contribute, but $bound is a subtype of ${spis.joinToString(" and ")}, and only " +
            "contributions to the SPI itself are collected. " +
            "Use @Contribute(${spis.joinToString { "${simple(it)}::class" }}) instead."

    fun uninjectableBound(name: String, bound: String, type: String, problem: KeyProblem): String =
        "$name lists $bound in @Binds, which binds $type, but ${keyProblem(type, problem)}. " +
            when (problem) {
                KeyProblem.ALL -> "Contribute each element with @Contribute instead."
                KeyProblem.UNQUALIFIED -> "Annotate $name with @Named(\"…\")."
                else -> "Remove $bound from @Binds."
            }

    fun notProvides(name: String, annotations: List<String>): String {
        val labels = annotations.joinToString(" and ") { annotationLabel(it) }
        return "$name is annotated $labels but not @Provides, so nothing binds what it returns. " +
            "Annotate it with @Provides, or remove $labels."
    }

    fun unreachableProvider(name: String, unreachable: Unreachable): String = when (unreachable) {
        Unreachable.Local ->
            "Provider $name is local, so the generated index cannot reach it. " +
                "Declare it at the top level or inside an object."

        is Unreachable.Hidden ->
            "Provider $name ${hiddenWhere(unreachable)}, so the generated index cannot call it. " +
                "Make it public or internal."
    }

    fun providerInClass(name: String, owner: String): String =
        "Provider $name is declared in $owner, which is not an object, so the container has no instance to call " +
            "it on. Move it to the top level or into an object."

    fun suspendProvider(name: String): String =
        "Provider $name is a suspend function, but the container creates instances without suspending. " +
            "Remove suspend, and do suspending work in Startable.start()."

    fun extensionProvider(name: String): String =
        "Provider $name is an extension function, so the container has no receiver to call it on. " +
            "Take the receiver as a parameter instead."

    fun genericProvider(name: String): String = "Provider $name has type parameters, so it has no single key. " +
        "Remove them, or declare one provider per concrete type."

    fun unresolvedReturn(name: String): String =
        "Provider $name has a return type that cannot be resolved. Declare the return type explicitly."

    fun unexpandedReturn(name: String, type: String): String =
        "Provider $name returns $type, a type alias that does not expand to a type. Return the aliased type itself."

    fun nullableProvider(name: String, type: String): String =
        "Provider $name returns $type, but a binding always has an instance. " +
            "Return a non-null type, and inject it as optional where it may be missing."

    fun unitProvider(name: String): String =
        "Provider $name returns Unit, so there is nothing to bind. Return the instance to bind."

    fun uninjectableReturn(name: String, type: String, problem: KeyProblem): String =
        "Provider $name returns $type, but ${keyProblem(type, problem)}. " +
            when (problem) {
                KeyProblem.ALL -> "Provide each element with its own @Provides @Contribute function instead."
                KeyProblem.LAZY, KeyProblem.FUNCTION -> "Return the instance itself."
                KeyProblem.UNQUALIFIED -> "Annotate it with @Named(\"…\")."
                KeyProblem.PLUGIN_LOCAL -> "Remove the provider, and inject the type where it is needed."
            }

    fun providedSpi(name: String, spi: String): String =
        "$name returns $spi itself, but $spi is an SPI whose implementations are contributed, not bound. " +
            "Return the implementing type and annotate the function with @Contribute(${simple(spi)}::class)."

    fun duplicateKey(key: String, first: String, second: String): String =
        "$key is bound twice in this plugin, by $first and by $second. " +
            "Remove one of them, or tell them apart with @Named."

    fun uncontributed(name: String, spi: String, contributes: Boolean, isObject: Boolean): String {
        val type = simple(spi)
        val remedy = when {
            isObject -> "Provide it with @Provides @Contribute($type::class) fun ${providerName(name)}() = " +
                "${simple(name)}, or make it a class annotated @Contribute($type::class)"

            contributes -> "Add $type::class to its @Contribute"

            else -> "Annotate it with @Contribute($type::class)"
        }
        return "$name implements $spi, but does not contribute to it, so it is never used as one. $remedy."
    }

    fun uncontributedProvider(name: String, type: String, spi: String, contributes: Boolean): String {
        val remedy = if (contributes) {
            "Add ${simple(spi)}::class to its @Contribute"
        } else {
            "Annotate it with @Contribute(${simple(spi)}::class)"
        }
        return "$name returns $type, which implements $spi, but does not contribute it, so it is never used as one. " +
            "$remedy."
    }

    fun unannotatedDependency(parameter: String, owner: String, type: String): String =
        "Parameter '$parameter' of $owner has type $type, a class of this plugin that is not a component, a " +
            "config section or returned by a @Provides function, so the container cannot create it. " +
            "Annotate $type with @Singleton, or provide it with a @Provides function."

    fun scopeBreak(parameter: String, owner: String, targets: List<String>): String =
        "Singleton $owner depends on channel-instance-scoped ${targets.joinToString(" and ")} through parameter " +
            "'$parameter'. Annotate $owner with @ChannelInstanceScoped or drop the dependency."

    fun unresolvedTypes(name: String): String =
        "$name refers to types that do not resolve, so it is not indexed. Fix the types, or the processor that " +
            "should generate them."

    fun sectionShape(name: String, shape: Shape): String =
        "@ConfigSection class $name is ${shapeWords(shape)}, but a section is decoded into a new instance of its " +
            "class. Make it a concrete class that is not inner."

    fun unreachableSection(name: String, unreachable: Unreachable): String =
        "@ConfigSection class " + unreachableClass(name, unreachable)

    fun genericSection(name: String): String = "@ConfigSection class $name has type parameters, so it has no " +
        "single key. Remove them, or annotate a subclass with concrete type arguments."

    fun notSerializable(name: String): String = "@ConfigSection class $name is not annotated @Serializable. " +
        "Annotate it with @kotlinx.serialization.Serializable so its section can be decoded."

    fun malformedPath(name: String, path: String): String = "The @ConfigSection path '$path' of $name is malformed. " +
        "Use \"\" for the plugin's config root, or dot-separated names that start with a letter and hold only " +
        "letters, digits, '-' and '_', such as \"cache.disk\"."

    fun reservedPath(name: String, path: String): String =
        "The @ConfigSection path '$path' of $name lies under '$ENABLED', which switches the plugin on and off. " +
            "Choose another path."

    fun duplicatePath(path: String, first: String, second: String): String =
        "Config section '$path' is declared by both $first and $second. Give each class its own path."

    /** `@Binds` for the annotation [name]. */
    fun annotationLabel(name: String): String = "@" + simple(name)

    private fun pluginIdRule(): String = "A plugin id is lowercase words of letters and digits, each starting " +
        "with a letter, joined by single hyphens, such as \"my-plugin\"."

    private fun keyProblem(type: String, problem: KeyProblem): String = when (problem) {
        KeyProblem.ALL ->
            "a parameter of type $type gets every contribution to its element type, so nothing can " +
                "inject this binding"

        KeyProblem.LAZY ->
            "a parameter of type $type resolves its type argument on first access, so nothing can " +
                "inject this binding"

        KeyProblem.FUNCTION ->
            "a parameter of a function type resolves its return type on every call, or is not " +
                "injected at all, so nothing can inject this binding"

        KeyProblem.UNQUALIFIED -> "$type is too general to bind without a qualifier, so nothing can inject it"

        KeyProblem.PLUGIN_LOCAL -> "the runtime binds $type for each plugin under the plugin's id"
    }

    private fun hiddenWhere(hidden: Unreachable.Hidden): String {
        val visibility = if (hidden.modifier == Modifier.PRIVATE) "private" else "protected"
        val kind = hidden.enclosingKind
        return if (hidden.enclosing == null || kind == null) {
            "is $visibility"
        } else {
            "is inside $visibility ${kindWords(kind)} ${hidden.enclosing}"
        }
    }

    private fun kindWords(kind: ClassKind): String = when (kind) {
        ClassKind.INTERFACE -> "interface"
        ClassKind.CLASS -> "class"
        ClassKind.ENUM_CLASS -> "enum class"
        ClassKind.ENUM_ENTRY -> "enum entry"
        ClassKind.OBJECT -> "object"
        ClassKind.ANNOTATION_CLASS -> "annotation class"
    }

    private fun shapeWords(shape: Shape): String = when (shape) {
        Shape.INTERFACE -> "an interface"
        Shape.OBJECT -> "an object"
        Shape.ENUM_CLASS -> "an enum class"
        Shape.ENUM_ENTRY -> "an enum entry"
        Shape.ANNOTATION_CLASS -> "an annotation class"
        Shape.ABSTRACT -> "abstract"
        Shape.INNER -> "an inner class"
    }

    private fun providerName(name: String): String = simple(name).replaceFirstChar(Char::lowercaseChar)

    private fun simple(name: String): String = name.substringAfterLast('.')
}
