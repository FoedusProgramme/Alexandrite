package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.Modifier
import kotlin.test.Test

class ErrorTest : FailingSamples() {
    // Parameters.

    @Test
    fun `a parameter with a default value is rejected`() {
        assertErrors(
            "@Singleton class Server(val clock: Clock = Clock())",
            "class Server" to Messages.defaultValue("clock", "sample.Server"),
        )
    }

    @Test
    fun `an unqualified parameter of a general type is rejected`() {
        val types = listOf(
            "String" to "kotlin.String",
            "Int" to "kotlin.Int",
            "Long?" to "kotlin.Long",
            "Boolean" to "kotlin.Boolean",
            "Char" to "kotlin.Char",
            "Double" to "kotlin.Double",
            "UInt" to "kotlin.UInt",
            "List<String>" to "kotlin.String",
            "Lazy<Int>" to "kotlin.Int",
            "() -> String" to "kotlin.String",
            "Port" to "kotlin.Int",
        )
        assertErrors(
            "typealias Port = Int\n" +
                types.withIndex().joinToString("\n") { (index, type) ->
                    "@Singleton class S$index(val v: ${type.first})"
                },
            *types.withIndex().map { (index, type) ->
                "class S$index(" to Messages.unqualified("v", "sample.S$index", type.second)
            }.toTypedArray(),
        )
    }

    @Test
    fun `a function type other than a provider is rejected`() {
        val types = listOf(
            "(String) -> Clock" to "(kotlin.String) -> sample.Clock",
            "suspend () -> Clock" to "suspend () -> sample.Clock",
            "Clock.() -> Unit" to "(sample.Clock) -> kotlin.Unit",
            "((String) -> Clock)?" to "((kotlin.String) -> sample.Clock)?",
        )
        assertErrors(
            types.withIndex().joinToString("\n") { (index, type) -> "@Singleton class S$index(val f: ${type.first})" },
            *types.withIndex().map { (index, type) ->
                "class S$index(" to Messages.functionType("f", "sample.S$index", type.second)
            }.toTypedArray(),
        )
    }

    @Test
    fun `a nullable list, lazy or provider is rejected`() {
        assertErrors(
            """
            @Singleton class All(val clocks: List<Clock>?)
            @Singleton class Later(val clock: Lazy<Clock>?)
            @Singleton class Make(val clock: (() -> Clock)?)
            """,
            "class All" to Messages.nullableWrapper(
                "clocks",
                "sample.All",
                "kotlin.collections.List<sample.Clock>?",
                DependencyKind.ALL,
            ),
            "class Later" to
                Messages.nullableWrapper("clock", "sample.Later", "kotlin.Lazy<sample.Clock>?", DependencyKind.LAZY),
            "class Make" to
                Messages.nullableWrapper("clock", "sample.Make", "(() -> sample.Clock)?", DependencyKind.PROVIDER),
        )
    }

    @Test
    fun `a nullable or star-projected type argument is rejected`() {
        val types = listOf(
            "List<Clock?>" to "kotlin.collections.List<sample.Clock?>",
            "Lazy<*>" to "kotlin.Lazy<*>",
            "() -> Clock?" to "() -> sample.Clock?",
        )
        assertErrors(
            types.withIndex().joinToString("\n") { (index, type) -> "@Singleton class S$index(val c: ${type.first})" },
            *types.withIndex().map { (index, type) ->
                "class S$index(" to Messages.projectedArgument("c", "sample.S$index", type.second)
            }.toTypedArray(),
        )
    }

    @Test
    fun `a vararg parameter is rejected`() {
        assertErrors(
            "@Singleton class Server(vararg val clocks: Clock)",
            "class Server" to Messages.vararg("clocks", "sample.Server"),
        )
    }

    @Test
    fun `an alias is expanded before the rules apply`() {
        assertErrors(
            """
            typealias Clocks = List<Clock>
            typealias MaybeClocks = Clocks?
            typealias Names<T> = Map<T, String>
            typealias MakeName = () -> String
            @Singleton class Aliased(val clocks: MaybeClocks, val make: MakeName, val names: Names<Clock?>)
            """,
            "class Aliased" to Messages.nullableWrapper(
                "clocks",
                "sample.Aliased",
                "kotlin.collections.List<sample.Clock>?",
                DependencyKind.ALL,
            ),
            "class Aliased" to Messages.unqualified("make", "sample.Aliased", "kotlin.String"),
        )
    }

    @Test
    fun `an alias that cannot be expanded is rejected`() {
        assertErrors(
            """
            typealias Out<T> = List<out T>
            typealias Id<T> = T
            @Singleton class Conflicting(val clocks: Out<in Clock>)
            @Singleton class Star(@Named("star") val value: Id<*>)
            """,
            "class Conflicting" to Messages.unexpandedParameter("clocks", "sample.Conflicting", "sample.Out"),
            "class Star" to Messages.unexpandedParameter("value", "sample.Star", "sample.Id"),
        )
    }

    // Classes.

    @Test
    fun `a component the container cannot create or reach is rejected`() {
        assertErrors(
            """
            @Singleton abstract class Abstract
            @Singleton sealed class Sealed
            @Singleton interface Api
            @Singleton object Single
            @Singleton enum class Mode { ON }
            @Singleton private class Hidden
            open class Outer {
                @Singleton inner class Inner
                @Singleton protected class Shielded
            }
            private class Private {
                @Singleton class Nested
            }
            @Singleton class Generic<T>
            fun helper() {
                @Singleton class Local
            }
            """,
            "class Abstract" to Messages.abstractComponent("sample.Abstract"),
            "class Sealed" to Messages.abstractComponent("sample.Sealed"),
            "interface Api" to Messages.interfaceComponent("sample.Api", "Api", spi = false),
            "object Single" to Messages.objectComponent("sample.Single"),
            "enum class Mode" to Messages.notAClass("sample.Mode", Shape.ENUM_CLASS),
            "class Hidden" to
                Messages.unreachableClass("sample.Hidden", Unreachable.Hidden(null, null, Modifier.PRIVATE)),
            "class Inner" to Messages.innerComponent("sample.Outer.Inner"),
            "class Shielded" to Messages.unreachableClass(
                "sample.Outer.Shielded",
                Unreachable.Hidden(null, null, Modifier.PROTECTED),
            ),
            "class Nested" to Messages.unreachableClass(
                "sample.Private.Nested",
                Unreachable.Hidden("sample.Private", ClassKind.CLASS, Modifier.PRIVATE),
            ),
            "class Generic" to Messages.genericClass("sample.Generic"),
            "class Local" to Messages.unreachableClass("Local", Unreachable.Local),
        )
    }

    @Test
    fun `an interface that is a contributed SPI is suggested for @Contribute`() {
        assertErrors(
            "@Singleton @ContributedSpi interface Extension",
            "interface Extension" to Messages.interfaceComponent("sample.Extension", "Extension", spi = true),
        )
    }

    @Test
    fun `a component with both scopes is rejected`() {
        assertErrors(
            "@Singleton @ChannelInstanceScoped class Both",
            "class Both" to Messages.twoScopes("sample.Both"),
        )
    }

    @Test
    fun `a component needs exactly one constructor the container can call`() {
        assertErrors(
            """
            @Singleton class Two(val clock: Clock) {
                constructor() : this(Clock())
            }
            @Singleton class TwoInjects @Inject constructor(val clock: Clock) {
                @Inject constructor() : this(Clock())
            }
            @Singleton class Locked private constructor()
            @Singleton class LockedInject @Inject private constructor(val clock: Clock) {
                constructor() : this(Clock())
            }
            """,
            "class Two(" to Messages.severalConstructors("sample.Two"),
            "class TwoInjects" to Messages.severalInject("sample.TwoInjects"),
            "class Locked " to Messages.noConstructor("sample.Locked"),
            "class LockedInject" to Messages.hiddenInject("sample.LockedInject"),
        )
    }

    @Test
    fun `a class that uses @Inject or @Named without a scope is rejected`() {
        assertErrors(
            """
            class Injected @Inject constructor(val clock: Clock)
            @Named("tagged") class Tagged
            @Named("both") class Both @Inject constructor()
            """,
            "class Injected" to Messages.strayClass("sample.Injected", listOf(INJECT)),
            "class Tagged" to Messages.strayClass("sample.Tagged", listOf(NAMED)),
            "class Both" to Messages.strayClass("sample.Both", listOf(INJECT, NAMED)),
        )
    }

    // Bound types.

    @Test
    fun `a bound type that is not a supertype is rejected`() {
        assertErrors(
            """
            interface Api
            interface Other
            @Binds(Other::class) class Impl : Api
            @Contribute(Runnable::class) class Tool
            @Binds(Itself::class) class Itself
            """,
            "class Impl" to Messages.notSupertype("sample.Impl", "@Binds", "sample.Other", returnType = false),
            "class Tool" to
                Messages.notSupertype("sample.Tool", "@Contribute", "java.lang.Runnable", returnType = false),
            "class Itself" to Messages.notSupertype("sample.Itself", "@Binds", "sample.Itself", returnType = false),
        )
    }

    @Test
    fun `a generic type bound only through a generic class is rejected`() {
        assertErrors(
            """
            interface Handler<T>
            abstract class Base<T> : Handler<T>
            @Binds(Handler::class) class StringHandler : Base<String>()
            """,
            "class StringHandler" to
                Messages.unknownArguments("sample.StringHandler", "@Binds", "sample.Handler", returnType = false),
        )
    }

    @Test
    fun `a type listed twice in one annotation is rejected`() {
        assertErrors(
            """
            interface Api
            interface Listener
            @Binds(Api::class, Api::class) class Twice : Api
            @Contribute(Listener::class, Listener::class) class Echo : Listener
            """,
            "class Twice" to Messages.listedTwice("sample.Twice", "@Binds", "sample.Api"),
            "class Echo" to Messages.listedTwice("sample.Echo", "@Contribute", "sample.Listener"),
        )
    }

    @Test
    fun `a bound type that nothing can inject is rejected`() {
        assertErrors(
            """
            @Binds(List::class) class Clocks : List<Clock> by emptyList()
            @Binds(Lazy::class) class LazyClock : Lazy<Clock> by lazy { Clock() }
            @Binds(Function0::class) class MakeClock : () -> Clock {
                override fun invoke(): Clock = Clock()
            }
            @Binds(PluginFiles::class) class Files : PluginFiles {
                override val dataDir: java.nio.file.Path get() = TODO()
            }
            """,
            "class Clocks" to Messages.uninjectableBound(
                "sample.Clocks",
                "kotlin.collections.List",
                "kotlin.collections.List<sample.Clock>",
                KeyProblem.ALL,
            ),
            "class LazyClock" to
                Messages.uninjectableBound(
                    "sample.LazyClock",
                    "kotlin.Lazy",
                    "kotlin.Lazy<sample.Clock>",
                    KeyProblem.LAZY,
                ),
            "class MakeClock" to
                Messages.uninjectableBound(
                    "sample.MakeClock",
                    "kotlin.Function0",
                    "() -> sample.Clock",
                    KeyProblem.FUNCTION,
                ),
            "class Files" to Messages.uninjectableBound(
                "sample.Files",
                "org.foedusprogramme.alexandrite.sdk.runtime.PluginFiles",
                "org.foedusprogramme.alexandrite.sdk.runtime.PluginFiles",
                KeyProblem.PLUGIN_LOCAL,
            ),
        )
    }

    // Providers.

    @Test
    fun `a provider the generated index cannot call is rejected`() {
        assertErrors(
            """
            class Holder {
                @Provides fun held(): Clock = Clock()
            }
            interface Api {
                @Provides fun api(): Clock = Clock()
            }
            private object Secret {
                @Provides fun kept(): Clock = Clock()
            }
            object Shelf {
                @Provides private fun hidden(): Clock = Clock()
            }
            fun outer() {
                @Provides fun local(): Clock = Clock()
            }
            """,
            "fun held" to Messages.providerInClass("sample.Holder.held()", "sample.Holder"),
            "fun api" to Messages.providerInClass("sample.Api.api()", "sample.Api"),
            "fun kept" to Messages.unreachableProvider(
                "sample.Secret.kept()",
                Unreachable.Hidden("sample.Secret", ClassKind.OBJECT, Modifier.PRIVATE),
            ),
            "fun hidden" to
                Messages.unreachableProvider("sample.Shelf.hidden()", Unreachable.Hidden(null, null, Modifier.PRIVATE)),
            "fun local" to Messages.unreachableProvider("local()", Unreachable.Local),
        )
    }

    @Test
    fun `a provider whose signature the container cannot use is rejected`() {
        assertErrors(
            """
            @Provides suspend fun suspended(): Clock = Clock()
            @Provides fun String.extended(): Clock = Clock()
            @Provides fun <T> generic(): Clock = Clock()
            @Provides fun nullable(): Clock? = null
            @Provides fun nothing() {}
            """,
            "fun suspended" to Messages.suspendProvider("sample.suspended()"),
            "fun String.extended" to Messages.extensionProvider("sample.extended()"),
            "fun <T> generic" to Messages.genericProvider("sample.generic()"),
            "fun nullable" to Messages.nullableProvider("sample.nullable()", "sample.Clock?"),
            "fun nothing" to Messages.unitProvider("sample.nothing()"),
        )
    }

    @Test
    fun `a provider whose return type nothing can inject is rejected`() {
        assertErrors(
            """
            @Provides fun clocks(): List<Clock> = emptyList()
            @Provides fun later(): Lazy<Clock> = lazy { Clock() }
            @Provides fun make(): () -> Clock = { Clock() }
            @Provides fun port(): Int = 8080
            @Provides @Named("port") fun namedPort(): Int = 8080
            @Provides fun files(): PluginFiles = TODO()
            """,
            "fun clocks" to Messages.uninjectableReturn(
                "sample.clocks()",
                "kotlin.collections.List<sample.Clock>",
                KeyProblem.ALL,
            ),
            "fun later" to Messages.uninjectableReturn("sample.later()", "kotlin.Lazy<sample.Clock>", KeyProblem.LAZY),
            "fun make" to Messages.uninjectableReturn("sample.make()", "() -> sample.Clock", KeyProblem.FUNCTION),
            "fun port" to Messages.uninjectableReturn("sample.port()", "kotlin.Int", KeyProblem.UNQUALIFIED),
            "fun files" to Messages.uninjectableReturn(
                "sample.files()",
                "org.foedusprogramme.alexandrite.sdk.runtime.PluginFiles",
                KeyProblem.PLUGIN_LOCAL,
            ),
        )
    }

    @Test
    fun `a provider's parameters follow the rules of constructor parameters`() {
        assertErrors(
            """
            @Provides
            fun server(
                clock: Clock = Clock(),
                port: Int,
                vararg names: String,
            ): Runnable = Runnable {}
            """,
            "clock: Clock" to Messages.defaultValue("clock", "sample.server()"),
            "port: Int" to Messages.unqualified("port", "sample.server()", "kotlin.Int"),
            "vararg names" to Messages.vararg("names", "sample.server()"),
        )
    }

    @Test
    fun `a provider's scopes and bound types follow the rules of classes`() {
        assertErrors(
            """
            @Provides @Singleton @ChannelInstanceScoped fun both(): Clock = Clock()
            @Provides @Binds(Runnable::class) @Named("bound") fun bound(): Clock = Clock()
            interface Handler<T>
            interface Wrapper<T> : Handler<T>
            @Provides @Binds(Handler::class) fun wrapper(): Wrapper<String> = object : Wrapper<String> {}
            """,
            "fun both" to Messages.twoScopes("sample.both()"),
            "fun bound" to Messages.notSupertype("sample.bound()", "@Binds", "java.lang.Runnable", returnType = true),
            "fun wrapper" to
                Messages.unknownArguments("sample.wrapper()", "@Binds", "sample.Handler", returnType = true),
        )
    }

    @Test
    fun `two bindings of one key in a plugin are rejected`() {
        assertErrors(
            """
            @Provides fun first(): Clock = Clock()
            @Provides fun second(): Clock = Clock()
            @Provides @Named("utc") fun utc(): Clock = Clock()
            interface Api
            @Binds(Api::class) class One : Api
            object Apis {
                @Provides @Binds(Api::class) fun other(): Other = Other()
            }
            class Other : Api
            """,
            "fun second" to Messages.duplicateKey("sample.Clock", "sample.first()", "sample.second()"),
            "class One" to Messages.duplicateKey("sample.Api", "sample.Apis.other()", "sample.One"),
        )
    }

    @Test
    fun `a function with binding annotations but without @Provides is rejected`() {
        assertErrors(
            """
            @Singleton fun lonely(): Clock = Clock()
            @Named("stray") @Binds(Runnable::class) fun stray(): Runnable = Runnable {}
            """,
            "fun lonely" to Messages.notProvides("sample.lonely()", listOf(SINGLETON)),
            "fun stray" to Messages.notProvides("sample.stray()", listOf(BINDS, NAMED)),
        )
    }

    // Config sections.

    @Test
    fun `a config section that is not serializable is rejected`() {
        assertErrors(
            "@ConfigSection(\"exec\") class ExecConfig(val command: String)",
            "class ExecConfig" to Messages.notSerializable("sample.ExecConfig"),
        )
    }

    @Test
    fun `a config section that is not a concrete class is rejected`() {
        assertErrors(
            """
            @ConfigSection("a") @Serializable object Single
            @ConfigSection("b") @Serializable enum class Mode { ON }
            @ConfigSection("c") @Serializable abstract class Base
            @ConfigSection("d") @Serializable sealed class Closed
            @ConfigSection("e") interface Api
            open class Outer {
                @ConfigSection("f") @Serializable inner class Inner
            }
            @ConfigSection("g") @Serializable private class Hidden
            """,
            "object Single" to Messages.sectionShape("sample.Single", Shape.OBJECT),
            "enum class Mode" to Messages.sectionShape("sample.Mode", Shape.ENUM_CLASS),
            "class Base" to Messages.sectionShape("sample.Base", Shape.ABSTRACT),
            "class Closed" to Messages.sectionShape("sample.Closed", Shape.ABSTRACT),
            "interface Api" to Messages.sectionShape("sample.Api", Shape.INTERFACE),
            "interface Api" to Messages.notSerializable("sample.Api"),
            "class Inner" to Messages.sectionShape("sample.Outer.Inner", Shape.INNER),
            "class Hidden" to
                Messages.unreachableSection("sample.Hidden", Unreachable.Hidden(null, null, Modifier.PRIVATE)),
        )
    }

    @Test
    fun `two config sections with one path are rejected`() {
        assertErrors(
            """
            @ConfigSection("exec") @Serializable class First(val command: String = "")
            @ConfigSection("exec") @Serializable class Second(val command: String = "")
            @ConfigSection @Serializable class Root(val name: String = "")
            @ConfigSection("") @Serializable class OtherRoot(val name: String = "")
            """,
            "class Second" to Messages.duplicatePath("exec", "sample.First", "sample.Second"),
            "class Root" to Messages.duplicatePath("", "sample.OtherRoot", "sample.Root"),
        )
    }

    @Test
    fun `a malformed config section path is rejected`() {
        val paths = listOf("tools..exec", ".exec", "exec.", "1exec", "_exec", "exec.-x", "my exec", "exec/x")
        assertErrors(
            paths.withIndex().joinToString("\n") { (index, path) ->
                "@ConfigSection(\"$path\") @Serializable class S$index(val command: String = \"\")"
            },
            *paths.withIndex().map { (index, path) ->
                "class S$index(" to Messages.malformedPath("sample.S$index", path)
            }.toTypedArray(),
        )
    }

    @Test
    fun `a config section under the enabled switch is rejected`() {
        assertErrors(
            """
            @ConfigSection("enabled") @Serializable class Switch(val on: Boolean = true)
            @ConfigSection("enabled.extra") @Serializable class Extra(val on: Boolean = true)
            """,
            "class Switch" to Messages.reservedPath("sample.Switch", "enabled"),
            "class Extra" to Messages.reservedPath("sample.Extra", "enabled.extra"),
        )
    }

    @Test
    fun `a config section cannot also be a component`() {
        assertErrors(
            "@Singleton @ConfigSection(\"exec\") @Serializable class ExecConfig(val command: String = \"\")",
            "class ExecConfig" to Messages.sectionComponent("sample.ExecConfig"),
        )
    }

    // Generated code.

    @Test
    fun `a declaration in the index's package that hides a package the index refers to is rejected`() {
        assertErrors(
            """
            @Singleton class Engine(@Named("name") val name: String)
            val sample = 0
            class kotlin
            """,
            "val sample" to Messages.hiddenPackage("sample.sample", "sample", "sample"),
            "class kotlin" to Messages.hiddenPackage("sample.kotlin", "kotlin", "sample"),
        )
    }

    @Test
    fun `a property with an explicit backing field hides a package like any other declaration`() {
        assertErrors(
            """
            @Singleton class Engine(@Named("name") val name: String)
            val kotlin: List<Int>
                field = mutableListOf()
            """,
            "val kotlin" to Messages.hiddenPackage("sample.kotlin", "kotlin", "sample"),
        )
    }
}
