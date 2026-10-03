package org.foedusprogramme.alexandrite.ksp

import kotlin.test.Test
import kotlin.test.assertFalse

class ErrorTest : FailingSamples() {
    // Parameters.

    @Test
    fun `a parameter with a default value is rejected`() {
        val messages = errors("@Singleton class Server(val clock: Clock = Clock())")

        assertReported(
            messages,
            "Parameter 'clock' of sample.Server has a default value",
            "Defaults are not allowed on injected parameters",
            "the value would be silently ignored",
        )
    }

    @Test
    fun `an unqualified parameter of a general type is rejected`() {
        val types = mapOf(
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
        ).entries.toList()
        val messages = errors(
            "typealias Port = Int\n" +
                types.withIndex().joinToString("\n") { (index, type) ->
                    "@Singleton class S$index(val v: ${type.key})"
                },
        )

        for ((index, type) in types.withIndex()) {
            assertReported(
                messages,
                "Parameter 'v' of sample.S$index has type ${type.value}, which is too general",
                "@Named",
                "@ConfigSection class",
            )
        }
    }

    @Test
    fun `a function type other than a provider is rejected`() {
        val types = listOf("(String) -> Clock", "suspend () -> Clock", "Clock.() -> Unit", "(() -> Clock)?")
        val messages =
            errors(types.withIndex().joinToString("\n") { (index, type) -> "@Singleton class S$index(val f: $type)" })

        for (index in types.indices) {
            assertReported(messages, "Parameter 'f' of sample.S$index has function type", "Inject () -> T or Lazy<T>")
        }
    }

    @Test
    fun `a nullable or star-projected type argument is rejected`() {
        val types = listOf("List<Clock?>", "Lazy<*>", "() -> Clock?")
        val messages =
            errors(types.withIndex().joinToString("\n") { (index, type) -> "@Singleton class S$index(val c: $type)" })

        for (index in types.indices) {
            assertReported(messages, "Parameter 'c' of sample.S$index has type", "nullable or a star projection")
        }
    }

    @Test
    fun `a vararg parameter is rejected`() {
        val messages = errors("@Singleton class Server(vararg val clocks: Clock)")

        assertReported(messages, "Parameter 'clocks' of sample.Server is a vararg", "Inject List<T>")
    }

    // Classes.

    @Test
    fun `a component the container cannot create or reach is rejected`() {
        val messages = errors(
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
        )

        assertReported(messages, "sample.Abstract is abstract", "concrete subclass")
        assertReported(messages, "sample.Sealed is abstract")
        assertReported(messages, "sample.Api is an interface", "@Binds(Api::class)")
        assertReported(messages, "sample.Single is an object", "Make it a class")
        assertReported(messages, "sample.Mode is an enum class")
        assertReported(messages, "sample.Hidden is private", "Make it public or internal")
        assertReported(messages, "sample.Outer.Inner is an inner class", "Remove the inner modifier")
        assertReported(messages, "sample.Outer.Shielded is protected")
        assertReported(messages, "sample.Private.Nested is inside private class sample.Private")
        assertReported(messages, "sample.Generic has type parameters")
        assertReported(messages, "Local is local", "Declare it at the top level")
    }

    @Test
    fun `a component with both scopes is rejected`() {
        val messages = errors("@Singleton @ChannelInstanceScoped class Both")

        assertReported(messages, "sample.Both is annotated both @Singleton and @ChannelInstanceScoped")
    }

    @Test
    fun `a component needs exactly one constructor the container can call`() {
        val messages = errors(
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
        )

        assertReported(messages, "sample.Two has several constructors and none is annotated @Inject")
        assertReported(messages, "sample.TwoInjects has several @Inject constructors")
        assertReported(messages, "sample.Locked has no public or internal constructor")
        assertReported(messages, "The @Inject constructor of sample.LockedInject is private or protected")
    }

    // Bound types.

    @Test
    fun `a bound type that is not a supertype is rejected`() {
        val messages = errors(
            """
            interface Api
            interface Other
            @Binds(Other::class) class Impl : Api
            @Contribute(Runnable::class) class Tool
            @Binds(Itself::class) class Itself
            """,
        )

        assertReported(
            messages,
            "sample.Impl lists sample.Other in @Binds, but sample.Other is not a supertype of sample.Impl",
        )
        assertReported(messages, "sample.Tool lists java.lang.Runnable in @Contribute", "not a supertype")
        assertReported(messages, "sample.Itself lists sample.Itself in @Binds", "not a supertype")
    }

    @Test
    fun `a generic type bound only through a generic class is rejected`() {
        val messages = errors(
            """
            interface Handler<T>
            abstract class Base<T> : Handler<T>
            @Binds(Handler::class) class StringHandler : Base<String>()
            """,
        )

        assertReported(messages, "sample.StringHandler lists sample.Handler in @Binds", "type arguments are unknown")
    }

    // Providers.

    @Test
    fun `a provider the generated index cannot call is rejected`() {
        val messages = errors(
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
        )

        assertReported(
            messages,
            "Provider sample.Holder.held() is declared in sample.Holder, which is not an object",
            "Move it to the top level or into an object",
            at = "fun held",
        )
        assertReported(messages, "Provider sample.Api.api() is declared in sample.Api", at = "fun api")
        assertReported(
            messages,
            "Provider sample.Secret.kept() is inside private object sample.Secret",
            "Make it public or internal",
            at = "fun kept",
        )
        assertReported(messages, "Provider sample.Shelf.hidden() is private", at = "fun hidden")
        assertReported(messages, "Provider local() is local", "Declare it at the top level", at = "fun local")
    }

    @Test
    fun `a provider whose signature the container cannot use is rejected`() {
        val messages = errors(
            """
            @Provides suspend fun suspended(): Clock = Clock()
            @Provides fun String.extended(): Clock = Clock()
            @Provides fun <T> generic(): Clock = Clock()
            @Provides fun nullable(): Clock? = null
            @Provides fun nothing() {}
            """,
        )

        assertReported(messages, "Provider sample.suspended() is a suspend function", at = "fun suspended")
        assertReported(messages, "Provider sample.extended() is an extension function", at = "fun String.extended")
        assertReported(messages, "Provider sample.generic() has type parameters", at = "fun <T> generic")
        assertReported(
            messages,
            "Provider sample.nullable() returns sample.Clock?, but a binding always has an instance",
            at = "fun nullable",
        )
        assertReported(messages, "Provider sample.nothing() returns Unit", at = "fun nothing")
    }

    @Test
    fun `a provider's parameters follow the rules of constructor parameters`() {
        val messages = errors(
            """
            @Provides
            fun server(
                clock: Clock = Clock(),
                port: Int,
                vararg names: String,
            ): Runnable = Runnable {}
            """,
        )

        assertReported(messages, "Parameter 'clock' of sample.server() has a default value", at = "clock: Clock")
        assertReported(messages, "Parameter 'port' of sample.server() has type kotlin.Int", at = "port: Int")
        assertReported(messages, "Parameter 'names' of sample.server() is a vararg", at = "vararg names")
    }

    @Test
    fun `a provider's scopes and bound types follow the rules of classes`() {
        val messages = errors(
            """
            @Provides @Singleton @ChannelInstanceScoped fun both(): Clock = Clock()
            @Provides @Binds(Runnable::class) @Named("bound") fun bound(): Clock = Clock()
            interface Handler<T>
            interface Wrapper<T> : Handler<T>
            @Provides @Binds(Handler::class) fun wrapper(): Wrapper<String> = object : Wrapper<String> {}
            """,
        )

        assertReported(
            messages,
            "sample.both() is annotated both @Singleton and @ChannelInstanceScoped",
            at = "fun both",
        )
        assertReported(
            messages,
            "sample.bound() lists java.lang.Runnable in @Binds, but java.lang.Runnable is not a supertype of its " +
                "return type",
            "Return a subtype of java.lang.Runnable",
            at = "fun bound",
        )
        assertReported(
            messages,
            "sample.wrapper() lists sample.Handler in @Binds, but its return type inherits it through a generic class",
            at = "fun wrapper",
        )
    }

    @Test
    fun `two bindings of one key in a module are rejected`() {
        val messages = errors(
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
        )

        assertReported(
            messages,
            "sample.Clock is bound twice in this module, by sample.first() and by sample.second()",
            "tell them apart with @Named",
            at = "fun second",
        )
        assertReported(messages, "sample.Api is bound twice in this module, by sample.One and by sample.Apis.other()")
        assertFalse("@Named(\"utc\") sample.Clock is bound twice" in messages, messages)
    }

    @Test
    fun `a function with binding annotations but without @Provides is rejected`() {
        val messages = errors(
            """
            @Singleton fun lonely(): Clock = Clock()
            @Named("stray") @Binds(Runnable::class) fun stray(): Runnable = Runnable {}
            """,
        )

        assertReported(
            messages,
            "sample.lonely() is annotated @Singleton but not @Provides",
            "Annotate it with @Provides, or remove @Singleton",
            at = "fun lonely",
        )
        assertReported(messages, "sample.stray() is annotated @Binds and @Named but not @Provides", at = "fun stray")
    }

    // Config sections.

    @Test
    fun `a config section that is not serializable is rejected`() {
        val messages = errors("@ConfigSection(\"exec\") class ExecConfig(val command: String)")

        assertReported(messages, "@ConfigSection class sample.ExecConfig is not annotated @Serializable")
    }

    @Test
    fun `two config sections with one path are rejected`() {
        val messages = errors(
            """
            @ConfigSection("exec") @Serializable class First(val command: String = "")
            @ConfigSection("exec") @Serializable class Second(val command: String = "")
            @ConfigSection @Serializable class Root(val name: String = "")
            @ConfigSection("") @Serializable class OtherRoot(val name: String = "")
            """,
        )

        assertReported(messages, "Config section 'exec' is declared by both sample.First and sample.Second")
        assertReported(messages, "Config section '' is declared by both sample.OtherRoot and sample.Root")
    }

    @Test
    fun `a malformed config section path is rejected`() {
        val paths = listOf("tools..exec", ".exec", "exec.", "1exec", "_exec", "exec.-x", "my exec", "exec/x")
        val messages = errors(
            paths.withIndex().joinToString("\n") { (index, path) ->
                "@ConfigSection(\"$path\") @Serializable class S$index(val command: String = \"\")"
            },
        )

        for ((index, path) in paths.withIndex()) {
            assertReported(
                messages,
                "The @ConfigSection path '$path' of sample.S$index is malformed",
                "Use \"\" for the module's config root",
            )
        }
    }

    @Test
    fun `a config section under the enabled switch is rejected`() {
        val messages = errors(
            """
            @ConfigSection("enabled") @Serializable class Switch(val on: Boolean = true)
            @ConfigSection("enabled.extra") @Serializable class Extra(val on: Boolean = true)
            """,
        )

        assertReported(messages, "The @ConfigSection path 'enabled' of sample.Switch lies under 'enabled'")
        assertReported(messages, "The @ConfigSection path 'enabled.extra' of sample.Extra lies under 'enabled'")
    }

    @Test
    fun `a config section cannot also be a component`() {
        val messages =
            errors("@Singleton @ConfigSection(\"exec\") @Serializable class ExecConfig(val command: String = \"\")")

        assertReported(messages, "sample.ExecConfig is a @ConfigSection, so it cannot also be a component")
    }
}
