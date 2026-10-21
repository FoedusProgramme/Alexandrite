package org.unitedsoftware.alexandrite.provider.lmstudio

import org.unitedsoftware.alexandrite.provider.common.EndpointProvider
import org.unitedsoftware.alexandrite.provider.common.chat.ChatEndpoint
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.ModelProvider

@Singleton
@Contribute(ModelProvider::class)
internal class LmStudioProvider(config: LmStudioConfig) :
    EndpointProvider<ChatEndpoint>(
        config.endpoints.map { (id, endpoint) ->
            ChatEndpoint(id, endpoint.settings, LMSTUDIO_FLAVOR)
        },
    )
