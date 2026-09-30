package org.foedusprogramme.alexandrite.app

import org.foedusprogramme.alexandrite.agent.AgentPlaceholder
import org.foedusprogramme.alexandrite.channel.telegram.TelegramPlaceholder
import org.foedusprogramme.alexandrite.common.CommonPlaceholder
import org.foedusprogramme.alexandrite.provider.anthropic.AnthropicPlaceholder
import org.foedusprogramme.alexandrite.provider.openaicompat.OpenAiCompatPlaceholder
import org.foedusprogramme.alexandrite.sdk.SdkPlaceholder
import org.foedusprogramme.alexandrite.tools.ToolsPlaceholder
import org.slf4j.LoggerFactory
import java.util.Properties

// Placeholder entry point: logs the startup line and exits 0. Startup wiring comes in a later task.

private val logger = LoggerFactory.getLogger("org.foedusprogramme.alexandrite.app.Main")

private const val VERSION_RESOURCE = "/alexandrite-version.properties"

internal val alexandriteVersion: String by lazy {
    val stream = checkNotNull(object {}.javaClass.getResourceAsStream(VERSION_RESOURCE)) {
        "$VERSION_RESOURCE is missing from the classpath"
    }
    val properties = Properties()
    stream.use { properties.load(it) }
    checkNotNull(properties.getProperty("version")) { "$VERSION_RESOURCE has no version" }
}

internal fun assembledModules(): List<String> = listOf(
    SdkPlaceholder.module,
    CommonPlaceholder.module,
    AgentPlaceholder.module,
    ToolsPlaceholder.module,
    TelegramPlaceholder.module,
    OpenAiCompatPlaceholder.module,
    AnthropicPlaceholder.module,
)

internal fun startupLine(): String = "Alexandrite $alexandriteVersion starting"

fun main() {
    logger.info(startupLine())
    logger.debug("Assembled modules: {}", assembledModules())
}
