package org.foedusprogramme.alexandrite.channel.onebot.channel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.channel.onebot.config.OneBotConfig
import org.foedusprogramme.alexandrite.channel.onebot.config.OneBotInstanceConfig
import org.foedusprogramme.alexandrite.channel.onebot.config.OneBotTransport
import org.foedusprogramme.alexandrite.channel.onebot.protocol.event.OneBotEvent
import org.foedusprogramme.alexandrite.channel.onebot.protocol.result.OneBotFailure
import org.foedusprogramme.alexandrite.channel.onebot.protocol.result.OneBotFailureKind
import org.foedusprogramme.alexandrite.channel.onebot.protocol.result.OneBotResult
import org.foedusprogramme.alexandrite.channel.onebot.transport.OneBotForwardWebSocket
import org.foedusprogramme.alexandrite.channel.onebot.transport.OneBotHttpApi
import org.foedusprogramme.alexandrite.channel.onebot.transport.OneBotHttpPostReceiver
import org.foedusprogramme.alexandrite.channel.onebot.transport.OneBotReverseWebSocket
import org.foedusprogramme.alexandrite.channel.onebot.transport.OneBotSettings
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle

/**
 * The connection of one channel instance, whichever transport its config chose.
 *
 * This is the one place where the plugin picks a transport and opens it, so that the channel above it calls an
 * action without knowing whether an HTTP request, a Websocket or a listener carries it. The instance scope owns it,
 * so the container starts it when the instance opens and closes it when the instance stops.
 */
@ChannelInstanceScoped
internal class OneBotLink(private val config: OneBotConfig, private val instanceConfig: OneBotInstanceConfig) :
    Lifecycle {
    private val settings = OneBotSettings.of(config, instanceConfig)

    private val http: OneBotHttpApi? = if (instanceConfig.kind == OneBotTransport.HTTP) {
        OneBotHttpApi(
            endpoint = settings.requireEndpoint().trimEnd('/'),
            token = settings.token,
            connectTimeoutMillis = settings.connectTimeoutMillis,
            firstByteTimeoutMillis = settings.firstByteTimeoutMillis,
            idleTimeoutMillis = settings.idleTimeoutMillis,
        )
    } else {
        null
    }

    private val receiver: OneBotHttpPostReceiver? = if (instanceConfig.kind == OneBotTransport.HTTP_POST) {
        OneBotHttpPostReceiver(settings)
    } else {
        null
    }

    private val forward: OneBotForwardWebSocket? = if (instanceConfig.kind == OneBotTransport.WS) {
        OneBotForwardWebSocket(settings)
    } else {
        null
    }

    private val reverse: OneBotReverseWebSocket? = if (instanceConfig.kind == OneBotTransport.WS_REVERSE) {
        OneBotReverseWebSocket(settings)
    } else {
        null
    }

    /** The events of the implementation, from whichever transport reports them. */
    internal val events: Flow<OneBotEvent> = when {
        receiver != null -> receiver.events
        forward != null -> forward.events
        reverse != null -> reverse.events
        else -> emptyFlow()
    }

    /** Runs one call of [action], with the suffix the caller chose already applied. */
    internal suspend fun call(action: String, params: JsonObject): OneBotResult<JsonObject> = when {
        http != null -> http.call(action, params)

        forward != null -> OneBotResult.Ok(forward.send(action, params))

        reverse != null -> OneBotResult.Ok(reverse.send(action, params))

        else -> OneBotResult.Unreachable(
            OneBotFailure(
                OneBotFailureKind.HTTP_STATUS,
                "A transport of '${instanceConfig.transport}' only takes reports; it has no endpoint to call.",
            ),
        )
    }

    /** The port a listener took, which a test reads and a log may name. */
    internal val listenerPort: Int? get() = receiver?.port ?: reverse?.port

    override suspend fun onStart() {
        forward?.start()
        reverse?.start()
        receiver?.start()
    }

    override fun onStop() {
        http?.close()
        receiver?.close()
        forward?.close()
        reverse?.close()
    }
}
