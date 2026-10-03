package org.foedusprogramme.alexandrite.ksp

import org.foedusprogramme.alexandrite.sdk.di.Container
import org.foedusprogramme.alexandrite.sdk.di.DiException
import org.foedusprogramme.alexandrite.sdk.di.DiProblemKind
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

    private fun channelPlugin(id: String): Compiled = compile(
        workingDir.resolve(id),
        source(
            "Bot.kt",
            """
            package sample.$id

            import org.foedusprogramme.alexandrite.ksp.Probe
            import org.foedusprogramme.alexandrite.ksp.Recorder
            import org.foedusprogramme.alexandrite.sdk.di.Binds
            import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
            import org.foedusprogramme.alexandrite.sdk.di.Named
            import org.foedusprogramme.alexandrite.sdk.di.Provides
            import org.foedusprogramme.alexandrite.sdk.plugin.Plugin
            import org.foedusprogramme.alexandrite.sdk.runtime.PluginFiles

            @Plugin(name = "$id bot", description = "Chats on $id")
            class BotPlugin

            class Greeting(val text: String)

            @Provides
            fun greeting(files: PluginFiles): Greeting = Greeting("$id bot in " + files.dataDir)

            @ChannelInstanceScoped
            @Named("$id")
            @Binds(Probe::class)
            class Bot(private val greeting: Greeting, recorder: Recorder) : Probe {
                init {
                    recorder.record("$id bot")
                }

                override fun report(): String = greeting.text
            }
            """,
        ),
        options = sampleOptions(id),
    ).also { it.assertSucceeded() }

    private fun files(id: String) = instanceBinding(
        key<PluginFiles>(id),
        object : PluginFiles {
            override val dataDir: Path = Path.of("/data", id)
        },
        "runtime",
        "files of $id",
    )

    @Test
    fun `a channel instance container of one plugin builds only that plugin's channel-instance-scoped classes`() {
        channelPlugin("telegram").use { telegramPlugin ->
            channelPlugin("discord").use { discordPlugin ->
                val indexes = listOf(telegramPlugin.indexes().single(), discordPlugin.indexes().single())
                val overrides =
                    listOf(
                        files("telegram"),
                        files("discord"),
                        instanceBinding(key<Recorder>(), recorder, "test", "test"),
                    )

                Container.build(indexes.map { it.pluginBindings() }, overrides).use { root ->
                    val telegram = root.child("telegram bot", setOf("telegram"))

                    assertEquals(listOf("telegram bot"), recorder.all())
                    assertEquals("telegram bot in /data/telegram", telegram.get(key<Probe>("telegram")).report())
                    val error = assertFailsWith<DiException> { telegram.get(key<Probe>("discord")) }
                    assertEquals(DiProblemKind.UNLISTED_PLUGIN, error.problems.single().kind)

                    val discord = root.child("discord bot", setOf("discord"))
                    assertEquals(listOf("telegram bot", "discord bot"), recorder.all())
                    assertEquals("discord bot in /data/discord", discord.get(key<Probe>("discord")).report())
                }
            }
        }
    }

    @Test
    fun `each plugin's index and descriptor name its own entry class`() {
        channelPlugin("telegram").use { compiled ->
            val index = compiled.indexes().single()

            assertEquals("sample.telegram.BotPlugin", index.info.entryClass)
            assertEquals("telegram bot", index.info.name)
            assertEquals("Chats on telegram", index.info.description)
            assertEquals("\"sample.telegram.TelegramIndex\"", compiled.descriptor("telegram")["indexClass"].toString())
        }
    }
}
