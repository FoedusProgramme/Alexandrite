package org.unitedsoftware.alexandrite.ksp

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.di.container.Container
import org.unitedsoftware.alexandrite.sdk.di.container.DiException
import org.unitedsoftware.alexandrite.sdk.di.container.DiProblemKind
import org.unitedsoftware.alexandrite.sdk.di.container.instanceBinding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.plugin.PluginFiles
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

            import org.unitedsoftware.alexandrite.ksp.Probe
            import org.unitedsoftware.alexandrite.ksp.Recorder
            import org.unitedsoftware.alexandrite.sdk.di.Binds
            import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
            import org.unitedsoftware.alexandrite.sdk.di.Named
            import org.unitedsoftware.alexandrite.sdk.di.Provides
            import org.unitedsoftware.alexandrite.sdk.plugin.Plugin
            import org.unitedsoftware.alexandrite.sdk.plugin.PluginFiles

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
            override val cacheDir: Path = Path.of("/cache", id)
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
    fun `plugin code needs an opt-in to use the container or implement its scope, while its index opts in itself`() {
        val uses = source(
            "Uses.kt",
            """
            package sample

            import org.unitedsoftware.alexandrite.sdk.di.container.Container

            fun build(): Container = Container.build(emptyList())
            """.trimIndent(),
        )
        val implements = source(
            "Scope.kt",
            """
            package sample

            import org.unitedsoftware.alexandrite.sdk.plugin.PluginScope
            import kotlin.coroutines.EmptyCoroutineContext

            class Scope : PluginScope {
                override val coroutineContext = EmptyCoroutineContext
            }
            """.trimIndent(),
        )

        compile(workingDir, uses, implements, entry("sample")).use { compiled ->
            val errors = compiled.messages.lines().filter { it.startsWith("e: ") }

            assertFalse(compiled.succeeded)
            assertTrue(errors.all { "Internal Alexandrite API" in it }, compiled.messages)
            assertTrue(listOf("Uses.kt", "Scope.kt").all { file -> errors.any { file in it } }, compiled.messages)
        }
    }
}
