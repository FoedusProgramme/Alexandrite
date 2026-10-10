package org.foedusprogramme.alexandrite.commands

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.commands.support.TextKeys
import org.foedusprogramme.alexandrite.runtime.i18n.readTexts
import org.foedusprogramme.alexandrite.runtime.i18n.textsPath
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.sdk.turn.CommandSpec
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.RecordingAgentControl
import org.foedusprogramme.alexandrite.testkit.assertTextsComplete
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
            PluginHarness.builder(AlexandriteCommandsIndex()).agentControl(RecordingAgentControl()).build().run {
                specs = getAll<CommandHandler>().flatMap { it.commands }
            }
        }

        assertEquals(COMMANDS, specs.map { it.name }.sorted())
        for (spec in specs) assertEquals(english[TextKeys.description(spec.name)], spec.description, spec.name)
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

    @Test
    fun `the plugin's texts are complete in each language it ships`() {
        assertEquals(listOf(LanguageTag("en")), assertTextsComplete(PLUGIN))
    }

    private companion object {
        val COMMANDS = listOf(
            "agent",
            "cancel",
            "home",
            "language",
            "link",
            "model",
            "new",
            "reasoning",
            "settings",
            "trust",
            "unlink",
            "untrust",
        )
    }
}
