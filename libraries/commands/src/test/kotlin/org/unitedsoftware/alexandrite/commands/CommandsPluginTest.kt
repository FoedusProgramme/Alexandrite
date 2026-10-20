package org.unitedsoftware.alexandrite.commands

import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.commands.support.TextKeys
import org.unitedsoftware.alexandrite.runtime.TextCatalog
import org.unitedsoftware.alexandrite.runtime.i18n.readTexts
import org.unitedsoftware.alexandrite.runtime.i18n.textsPath
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RecordingAgent
import org.unitedsoftware.alexandrite.testkit.testCommand
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandsPluginTest {
    private val english: Map<String, String> =
        readTexts(checkNotNull(javaClass.classLoader.getResource(textsPath(PLUGIN, "en"))))

    @Test
    fun `the plugin contributes each command once, described by its English text`() {
        lateinit var specs: List<CommandSpec>

        runBlocking {
            PluginHarness.builder(AlexandriteCommandsIndex()).agent(RecordingAgent()).build().run {
                specs = getAll<CommandHandler>().flatMap { it.commands }
            }
        }

        assertEquals(COMMANDS, specs.map { it.name }.sorted())
        for (spec in specs) assertEquals(english[TextKeys.description(spec.name)], spec.description, spec.name)
    }

    @Test
    fun `each handler describes its commands in a language through its texts, its specs in the host's language`() {
        val catalog = TextCatalog.builder()
            .text(PLUGIN, CHINESE, TextKeys.description("new"), "开始新对话。")
            .text(PLUGIN, CHINESE, TextKeys.description("help"), "列出命令。")
            .build()
        lateinit var handlers: List<CommandHandler>

        runBlocking {
            PluginHarness.builder(AlexandriteCommandsIndex())
                .agent(RecordingAgent())
                .language(CHINESE)
                .texts(catalog)
                .build()
                .run { handlers = getAll<CommandHandler>() }
        }

        val byName = handlers.flatMap { handler -> handler.commands.map { it.name to handler } }.toMap()
        val new = byName.getValue("new")
        assertEquals("开始新对话。", new.description("new", CHINESE))
        assertEquals(english[TextKeys.description("new")], new.description("new", LanguageTag("en")))
        assertEquals("开始新对话。", new.commands.single { it.name == "new" }.description)
        assertEquals("列出命令。", byName.getValue("help").description("help", null))
        assertEquals(english[TextKeys.description("model")], byName.getValue("model").description("model", null))
    }

    @Test
    fun `a command that takes no arguments answers its usage when given some`() {
        val agent = RecordingAgent()

        runBlocking {
            PluginHarness.builder(AlexandriteCommandsIndex()).agent(agent).build().run {
                val byName = getAll<CommandHandler>().flatMap { handler -> handler.commands.map { it.name to handler } }
                for ((name, handler) in byName.toMap().filterKeys { it in NO_ARGUMENTS }) {
                    val reply = handler.answer(testCommand(name, "now"))
                    assertEquals("Send /$name without anything after it.", reply, name)
                }
            }
        }

        assertEquals(emptyList(), agent.calls)
    }

    @Test
    fun `the descriptions and usages in the texts belong to the declared commands`() {
        val described = english.keys.mapNotNull { Regex("command\\.(.+)\\.description").matchEntire(it) }
        val usages = english.keys.filter { it.startsWith("usage.") }.map { it.removePrefix("usage.") }

        assertEquals(COMMANDS, described.map { it.groupValues[1] }.sorted())
        assertEquals(COMMANDS - "link", usages.sorted())
    }

    @Test
    fun `the texts hold every key the code names and no other`() {
        val named = TextKeys::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
        val commandKeys = english.keys.filter { it.startsWith("command.") || it.startsWith("usage.") }

        assertEquals(english.keys - commandKeys.toSet(), named.toSet())
    }

    private companion object {
        val NO_ARGUMENTS = setOf("cancel", "context", "help", "new", "profiles", "settings")

        val COMMANDS = listOf(
            "agent",
            "allow",
            "cancel",
            "compact",
            "context",
            "help",
            "home",
            "jobs",
            "language",
            "link",
            "mode",
            "model",
            "new",
            "profiles",
            "reasoning",
            "settings",
            "timezone",
            "trust",
            "unlink",
            "untaint",
            "untrust",
            "usage",
        )
    }
}
