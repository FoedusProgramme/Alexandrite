package org.unitedsoftware.alexandrite.provider.lmstudio

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.provider.common.EndpointConfig
import org.unitedsoftware.alexandrite.provider.common.EndpointConfigSerializer
import org.unitedsoftware.alexandrite.provider.common.NoOptions
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId

@ConfigSection
@Serializable
internal class LmStudioConfig(
    /** The endpoints by their ids. */
    val endpoints: Map<
        EndpointId,
        @Serializable(with = EndpointSerializer::class)
        EndpointConfig<NoOptions>,
        > =
        emptyMap(),
)

internal object EndpointSerializer : EndpointConfigSerializer<NoOptions>(
    "org.unitedsoftware.alexandrite.provider.lmstudio.EndpointConfig",
    NoOptions.serializer(),
    baseUrl = "http://localhost:1234/v1",
)
