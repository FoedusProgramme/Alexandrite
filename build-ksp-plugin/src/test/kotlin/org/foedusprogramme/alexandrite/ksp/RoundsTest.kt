package org.foedusprogramme.alexandrite.ksp

import org.foedusprogramme.alexandrite.sdk.di.container.Container
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RoundsTest {
    @TempDir
    lateinit var workingDir: File

    private fun compileWithGenerated(code: String, generated: String): Compiled = compile(
        workingDir,
        source("Uses.kt", "package sample\n\nimport org.foedusprogramme.alexandrite.sdk.di.*\n\n" + code.trimIndent()),
        entry("sample"),
        extraProcessor = GeneratingProvider(
            "Generated",
            "package sample\n\nimport org.foedusprogramme.alexandrite.sdk.di.*\n\n" + generated.trimIndent(),
        ),
    )

    @Test
    fun `components of later rounds are indexed once every round has run`() {
        compileWithGenerated(
            "@Singleton class UsesGenerated(val generated: Generated)",
            "@Singleton class Generated",
        ).use { compiled ->
            compiled.assertSucceeded()

            val index = compiled.indexes().single()
            assertEquals(
                listOf("sample.Generated", "sample.SamplePlugin", "sample.UsesGenerated"),
                index.bindings().map { it.key.toString() },
            )
            Container.build(listOf(index.pluginBindings())).close()
        }
    }

    @Test
    fun `a provider of a later round binds a class that an earlier round depends on`() {
        compileWithGenerated(
            """
            class Settings
            @Singleton class UsesSettings(val settings: Settings)
            """,
            "@Provides fun settings(): Settings = Settings()",
        ).use { compiled ->
            compiled.assertSucceeded()

            val index = compiled.indexes().single()
            assertEquals(
                listOf("sample.SamplePlugin", "sample.UsesSettings", "sample.Settings"),
                index.bindings().map { it.key.toString() },
            )
            Container.build(listOf(index.pluginBindings())).close()
        }
    }

    @Test
    fun `a declaration whose types never resolve is reported, and nothing is generated`() {
        val uses = source(
            "Uses.kt",
            "package sample\n\nimport org.foedusprogramme.alexandrite.sdk.di.*\n\n@Singleton class UsesMissing(val m: Missing)",
        )
        compile(workingDir, uses, entry("sample")).use { compiled ->
            assertFalse(compiled.succeeded)
            assertEquals(
                listOf(Reported("Uses.kt", 5, Messages.unresolvedTypes("sample.UsesMissing"))),
                reported(compiled.messages),
            )
            assertEquals(emptyList(), compiled.generated())
        }
    }

    @Test
    fun `a scope break whose channel-instance-scoped side comes in a later round is rejected`() {
        compileWithGenerated(
            """
            interface Port
            @Singleton class Registry(val port: Port)
            """,
            "@ChannelInstanceScoped @Binds(Port::class) class GeneratedPort : Port",
        ).use { compiled ->
            assertFalse(compiled.succeeded)
            val expected = Messages.scopeBreak("port", "sample.Registry", listOf("sample.GeneratedPort"))
            assertEquals(listOf(Reported("Uses.kt", 6, expected)), reported(compiled.messages))
        }
    }
}
