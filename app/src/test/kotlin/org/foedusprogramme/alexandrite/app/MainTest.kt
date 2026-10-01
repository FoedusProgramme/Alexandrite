package org.foedusprogramme.alexandrite.app

import org.foedusprogramme.alexandrite.sdk.di.ModuleIndex
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainTest {
    @Test
    fun `startup line names the product and the expanded version`() {
        val line = startupLine()
        assertTrue(Regex("""Alexandrite \d+\.\d+\.\d+\S* starting""").matches(line), line)
    }

    @Test
    fun `every library is on the runtime classpath`() {
        assertEquals(8, assembledModules().toSet().size)
    }

    @Test
    fun `every built-in index is found in its module's package`() {
        val base = "org.foedusprogramme.alexandrite"
        assertEquals(
            mapOf(
                "alexandrite-agent" to "$base.agent.AlexandriteAgentIndex",
                "alexandrite-app" to "$base.app.AlexandriteAppIndex",
                "alexandrite-channel-telegram" to "$base.channel.telegram.AlexandriteChannelTelegramIndex",
                "alexandrite-provider-anthropic" to "$base.provider.anthropic.AlexandriteProviderAnthropicIndex",
                "alexandrite-provider-openai-compatible" to
                    "$base.provider.openaicompatible.AlexandriteProviderOpenaiCompatibleIndex",
                "alexandrite-tools" to "$base.tools.AlexandriteToolsIndex",
            ),
            ServiceLoader.load(ModuleIndex::class.java).associate { it.module to it.javaClass.name },
        )
    }

    @Test
    fun `main returns normally`() {
        main()
    }
}
