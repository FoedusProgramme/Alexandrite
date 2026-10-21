package org.unitedsoftware.alexandrite.agent.directory

import org.unitedsoftware.alexandrite.agent.prompt.PromptContributor
import org.unitedsoftware.alexandrite.agent.prompt.PromptOrder
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.PromptSection
import java.nio.file.Path

internal const val CONVERSATION_DIRECTORY_SECTION: String = "conversation_directory"

/** Where a conversation of an agent offered tools keeps its own files. */
@Singleton
@Contribute(PromptContributor::class)
internal class ConversationDirectoryPrompt(private val directories: ConversationDirectories) : PromptContributor {
    override val order: Int = PromptOrder.CONVERSATION_DIRECTORY

    override fun section(turn: TurnInfo, offered: Set<String>): PromptSection? =
        if (offered.isEmpty()) null else conversationDirectorySection(directories.path(turn))
}

/** Where this conversation keeps its own files, at [directory]. */
internal fun conversationDirectorySection(directory: Path): PromptSection = PromptSection(
    CONVERSATION_DIRECTORY_SECTION,
    listOf(
        "# Conversation directory",
        "This conversation has its own directory at $directory, private to it; writing there needs no approval, " +
            "and it and its subdirectories are created when you first write a file in them. Keep things there that " +
            "you must remember but not say yet, such as the answer of a game in progress; files the user sends land " +
            "in its inbox subdirectory.",
    ).joinToString("\n\n"),
    stable = true,
)
