package org.foedusprogramme.alexandrite.commands.chat

import org.foedusprogramme.alexandrite.commands.support.Replies
import org.foedusprogramme.alexandrite.commands.support.TextKeys
import org.foedusprogramme.alexandrite.commands.support.description
import org.foedusprogramme.alexandrite.commands.support.replies
import org.foedusprogramme.alexandrite.commands.support.spec
import org.foedusprogramme.alexandrite.sdk.agent.ProfileSource
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.CommandContext
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import org.foedusprogramme.alexandrite.sdk.turn.CommandSpec
import org.foedusprogramme.alexandrite.sdk.turn.ProfileSummary

/** `/profiles`. */
@Singleton
@Contribute(CommandHandler::class)
internal class ProfileCommands(private val control: AgentControl, private val texts: Texts) : CommandHandler {
    override val commands: List<CommandSpec> = listOf(texts.spec(PROFILES))

    override fun description(command: String, language: LanguageTag?): String = texts.description(command, language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val replies = texts.replies(context.turn.language)
        if (invocation.arguments.isNotBlank()) {
            context.reply(replies.usage(PROFILES))
            return
        }
        val profiles = control.profiles()
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
