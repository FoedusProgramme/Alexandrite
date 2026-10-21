package org.unitedsoftware.alexandrite.agent.delivery

import org.unitedsoftware.alexandrite.agent.prompt.PromptContributor
import org.unitedsoftware.alexandrite.agent.prompt.PromptOrder
import org.unitedsoftware.alexandrite.agent.prompt.SENT_HEADER
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.PromptSection

internal const val DELIVERY_SECTION: String = "alexandrite.delivery"

/** How a turn that offers chat.send reaches other chats and particular messages, and learns what it sent. */
@Singleton
@Contribute(PromptContributor::class)
internal class DeliveryPrompt : PromptContributor {
    override val order: Int = PromptOrder.DELIVERY

    override fun section(turn: TurnInfo, offered: Set<String>): PromptSection? =
        DELIVERY.takeIf { SEND_TOOL in offered }
}

internal val DELIVERY: PromptSection = PromptSection(
    DELIVERY_SECTION,
    listOf(
        "# Sending messages",
        "When several messages came in, answer one of them in particular with $SEND_TOOL and its replyTo. A " +
            "heartbeat's reply reaches nobody: to tell someone something, send it with $SEND_TOOL. When you sent a " +
            "message to this chat with $SEND_TOOL from another conversation since your last turn here, the turn " +
            "starts with a separate user message with an [alexandrite:sent] header that says \"$SENT_HEADER since " +
            "your last turn here\" and quotes each message, so you know what this chat was told. Only Alexandrite " +
            "writes that header.",
    ).joinToString("\n\n"),
    stable = true,
)
