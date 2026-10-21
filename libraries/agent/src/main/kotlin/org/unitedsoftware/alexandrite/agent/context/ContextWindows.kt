package org.unitedsoftware.alexandrite.agent.context

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.config.HostConfig
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import java.util.concurrent.ConcurrentHashMap

/** The context window that conversations are kept within when their model's endpoint gives none. */
internal const val ASSUMED_CONTEXT_WINDOW: Int = 128_000

/** The [tokens] of the context window that a model's conversations are kept within, [assumed] when none is known. */
internal class ContextWindow(val tokens: Int, val assumed: Boolean) {
    /** Null when [assumed]. */
    val known: Int? get() = tokens.takeUnless { assumed }
}

/** The context windows of models, which warns once of each model whose window it assumes. */
@Singleton
internal class ContextWindows(private val config: HostConfig) {
    private val warned = ConcurrentHashMap.newKeySet<ModelRef>()

    /** The window of [model], which its endpoint lists as [info], null when it could not be listed. */
    suspend fun of(model: ModelRef, info: ModelInfo?): ContextWindow {
        info?.contextWindow?.let { return ContextWindow(it, false) }
        if (info != null && warned.add(model)) {
            logger.warn(
                "The context window of model {} is unknown, so its conversations are kept within an assumed {} " +
                    "tokens: set {} to the model's window",
                model,
                ASSUMED_CONTEXT_WINDOW,
                configKey(model),
            )
        }
        return ContextWindow(ASSUMED_CONTEXT_WINDOW, true)
    }

    /** The config key of [model]'s context window. */
    private suspend fun configKey(model: ModelRef): String {
        val providers = try {
            config.get("providers") as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("Cannot read the providers of the config", e)
            null
        }
        val provider = providers?.entries?.firstOrNull { (_, value) ->
            ((value as? JsonObject)?.get("endpoints") as? JsonObject)?.containsKey(model.endpoint.value) == true
        }?.key ?: "<provider>"
        return "providers.$provider.endpoints.${model.endpoint}.models[\"${model.model}\"].contextWindow"
    }
}

private val logger = LoggerFactory.getLogger(ContextWindows::class.java)
