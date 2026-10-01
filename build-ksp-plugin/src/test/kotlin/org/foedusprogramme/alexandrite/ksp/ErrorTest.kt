package org.foedusprogramme.alexandrite.ksp

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class ErrorTest {
    @TempDir
    lateinit var workingDir: File

    private fun errors(code: String): String {
        val header = """
            package sample

            import kotlinx.serialization.Serializable
            import org.foedusprogramme.alexandrite.sdk.config.ConfigSection
            import org.foedusprogramme.alexandrite.sdk.di.*

            class Clock

        """.trimIndent()
        val compiled = compile(workingDir, source("Sample.kt", header + code.trimIndent()))
        assertFalse(compiled.succeeded, "the compilation should fail")
        return compiled.messages
    }

    /** Asserts that one error line holds all of [texts] and points into the sample. */
    private fun assertReported(messages: String, vararg texts: String) {
        val line = messages.lines().firstOrNull { line -> texts.all { it in line } }
        assertNotNull(line, "no error with ${texts.toList()} in:\n$messages")
        assertContains(line, "Sample.kt:")
    }

    // Parameters.

    @Test
    fun `a parameter with a default value is rejected`() {
        val messages = errors("@Singleton class Server(val clock: Clock = Clock())")

        assertReported(
            messages,
            "Parameter 'clock' of sample.Server has a default value",
            "Defaults are not allowed on injected constructors",
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
        val messages = errors("@Singleton @ChannelScoped class Both")

        assertReported(messages, "sample.Both is annotated both @Singleton and @ChannelScoped")
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

    // Config sections.

    @Test
    fun `a config section that is not serializable is rejected`() {
        val messages = errors("@ConfigSection(\"tools.exec\") class ExecConfig(val command: String)")

        assertReported(messages, "@ConfigSection class sample.ExecConfig is not annotated @Serializable")
    }

    @Test
    fun `two config sections with one path are rejected`() {
        val messages = errors(
            """
            @ConfigSection("tools.exec") @Serializable class First(val command: String = "")
            @ConfigSection("tools.exec") @Serializable class Second(val command: String = "")
            """,
        )

        assertReported(messages, "Config section 'tools.exec' is declared by both sample.First and sample.Second")
    }

    @Test
    fun `a malformed config section path is rejected`() {
        val messages =
            errors("@ConfigSection(\"tools..exec\") @Serializable class ExecConfig(val command: String = \"\")")

        assertReported(messages, "The @ConfigSection path 'tools..exec' of sample.ExecConfig is malformed")
    }

    @Test
    fun `a config section cannot also be a component`() {
        val messages =
            errors(
                "@Singleton @ConfigSection(\"tools.exec\") @Serializable class ExecConfig(val command: String = \"\")",
            )

        assertReported(messages, "sample.ExecConfig is a @ConfigSection, so it cannot also be a component")
    }
}
