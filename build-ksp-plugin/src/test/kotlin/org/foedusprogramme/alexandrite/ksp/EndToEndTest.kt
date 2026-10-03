package org.foedusprogramme.alexandrite.ksp

import org.foedusprogramme.alexandrite.sdk.di.Container
import org.foedusprogramme.alexandrite.sdk.di.DiException
import org.foedusprogramme.alexandrite.sdk.di.ModuleIndex
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind
import org.foedusprogramme.alexandrite.sdk.di.instanceBinding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.runtime.PluginFiles
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EndToEndTest {
    @TempDir
    lateinit var workingDir: File

    private val recorder = Recorder()

    private fun channelModule(module: String): ModuleIndex {
        val compiled = compile(
            workingDir.resolve(module).apply { mkdirs() },
            source(
                "Bot.kt",
                """
                package sample.$module

                import org.foedusprogramme.alexandrite.ksp.Probe
                import org.foedusprogramme.alexandrite.ksp.Recorder
                import org.foedusprogramme.alexandrite.sdk.di.Binds
                import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
                import org.foedusprogramme.alexandrite.sdk.di.Named
                import org.foedusprogramme.alexandrite.sdk.di.Provides
                import org.foedusprogramme.alexandrite.sdk.runtime.PluginFiles

                class Greeting(val text: String)

                @Provides
                fun greeting(files: PluginFiles): Greeting = Greeting("$module bot in " + files.dataDir)

                @ChannelInstanceScoped
                @Named("$module")
                @Binds(Probe::class)
                class Bot(private val greeting: Greeting, recorder: Recorder) : Probe {
                    init {
                        recorder.record("$module bot")
                    }

                    override fun report(): String = greeting.text
                }
                """,
            ),
            options = mapOf(MODULE_OPTION to module),
        )
        compiled.assertSucceeded()
        return compiled.indexes().single()
    }

    private fun files(module: String) = instanceBinding(
        key<PluginFiles>(module),
        object : PluginFiles {
            override val dataDir: Path = Path.of("/data", module)
        },
        "runtime",
        "files of $module",
    )

    @Test
    fun `a channel instance container of one module builds only that module's channel-instance-scoped classes`() {
        val indexes = listOf(channelModule("telegram"), channelModule("discord"))
        val overrides =
            listOf(files("telegram"), files("discord"), instanceBinding(key<Recorder>(), recorder, "test", "test"))

        Container.build(indexes, overrides).use { root ->
            val telegram = root.child("telegram bot", setOf("telegram"))

            assertEquals(listOf("telegram bot"), recorder.all())
            assertEquals("telegram bot in /data/telegram", telegram.get(key<Probe>("telegram")).report())
            val error = assertFailsWith<DiException> { telegram.get(key<Probe>("discord")) }
            assertEquals(ProblemKind.UNLISTED_MODULE, error.problems.single().kind)

            val discord = root.child("discord bot", setOf("discord"))
            assertEquals(listOf("telegram bot", "discord bot"), recorder.all())
            assertEquals("discord bot in /data/discord", discord.get(key<Probe>("discord")).report())
        }
    }
}
