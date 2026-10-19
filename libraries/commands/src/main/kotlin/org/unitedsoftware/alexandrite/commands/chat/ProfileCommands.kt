package org.unitedsoftware.alexandrite.commands.chat

import org.unitedsoftware.alexandrite.commands.support.Replies
import org.unitedsoftware.alexandrite.commands.support.TextKeys
import org.unitedsoftware.alexandrite.commands.support.description
import org.unitedsoftware.alexandrite.commands.support.replies
import org.unitedsoftware.alexandrite.commands.support.spec
import org.unitedsoftware.alexandrite.sdk.agent.ProfileSource
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.AgentCatalog
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import org.unitedsoftware.alexandrite.sdk.turn.ProfileSummary

/** `/profiles`. */
@Singleton
@Contribute(CommandHandler::class)
internal class ProfileCommands(private val catalog: AgentCatalog, private val texts: Texts) : CommandHandler {
    override val commands: List<CommandSpec> = listOf(texts.spec(PROFILES))

    override fun description(command: String, language: LanguageTag?): String = texts.description(command, language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val replies = texts.replies(context.turn.language)
        if (invocation.arguments.isNotBlank()) {
            context.reply(replies.usage(PROFILES))
            return
        }
        val profiles = catalog.profiles()
        if (profiles.isEmpty()) {
            context.reply(replies.text(TextKeys.PROFILES_NONE))
            return
        }
        val items = profiles.joinToString("\n") { item(it, replies) }
        context.reply(replies.text(TextKeys.PROFILES_LIST, "profiles" to items))
    }

    private fun item(summary: ProfileSummary, replies: Replies): String {
        val profile = summary.profile
        val model = profile.model?.toString() ?: replies.text(TextKeys.PROFILES_MODEL_DEFAULT)
        val mode = profile.mode?.toString() ?: replies.text(TextKeys.PROFILES_MODE_DEFAULT)
        return replies.text(
            TextKeys.PROFILES_ITEM,
            "id" to profile.id,
            "name" to profile.name,
            "description" to profile.description,
            "model" to model,
            "mode" to mode,
            "source" to source(summary.source, replies),
        )
    }

    private fun source(source: ProfileSource, replies: Replies): String = when (source) {
        is ProfileSource.Config -> replies.text(TextKeys.PROFILES_SOURCE_CONFIG)

        is ProfileSource.Plugin -> replies.text(TextKeys.PROFILES_SOURCE_PLUGIN)

        is ProfileSource.Created -> source.by?.let {
            replies.text(
                TextKeys.PROFILES_SOURCE_CREATED,
                "who" to it.displayName,
            )
        }
            ?: replies.text(TextKeys.PROFILES_SOURCE_CREATED_HOST)

        else -> source.toString()
    }

    private companion object {
        const val PROFILES = "profiles"
    }
}
