package org.unitedsoftware.alexandrite.sdk.turn

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.ContributedSpi

/** A command as a channel recognized it. */
@Poko
public class CommandInvocation private constructor(
    public val chat: ChatAddress,
    /** The command word without its prefix or the bot's mention, such as `new` for `/new@my_bot`. */
    public val name: String,
    /** The rest of the command, as written. */
    public val arguments: String,
    /** The message or interaction that carried the command, null when none did. */
    public val trigger: ChannelMessageRef?,
    public val issuer: ChatUser,
    /** The kind of [chat] as the channel saw it, [ChatKind.UNKNOWN] when it did not say. */
    public val chatKind: ChatKind,
) {
    init {
        require(name.isNotEmpty() && name.none { it.isWhitespace() || it.isISOControl() } && !name.startsWith('/')) {
            "Malformed command name '$name': it must be non-empty, hold no whitespace or control characters and " +
                "leave out the prefix."
        }
        require(trigger == null || trigger.chat == chat) {
            "The trigger of a command invocation in $chat is in ${trigger?.chat}."
        }
        require(issuer.address.instance == chat.instance) {
            "The issuer ${issuer.address} is no user of channel instance ${chat.instance}."
        }
    }

    public fun toBuilder(): Builder = Builder(chat, name, issuer)
        .arguments(arguments)
        .trigger(trigger)
        .chatKind(chatKind)

    public class Builder internal constructor(
        private var chat: ChatAddress,
        private var name: String,
        private var issuer: ChatUser,
    ) {
        private var arguments: String = ""
        private var trigger: ChannelMessageRef? = null
        private var chatKind: ChatKind = ChatKind.UNKNOWN

        public fun chat(chat: ChatAddress): Builder = apply { this.chat = chat }

        public fun name(name: String): Builder = apply { this.name = name }

        public fun arguments(arguments: String): Builder = apply { this.arguments = arguments }

        public fun trigger(trigger: ChannelMessageRef?): Builder = apply { this.trigger = trigger }

        public fun issuer(issuer: ChatUser): Builder = apply { this.issuer = issuer }

        public fun chatKind(chatKind: ChatKind): Builder = apply { this.chatKind = chatKind }

        public fun build(): CommandInvocation = CommandInvocation(chat, name, arguments, trigger, issuer, chatKind)
    }

    public companion object {
        public fun builder(chat: ChatAddress, name: String, issuer: ChatUser): Builder = Builder(chat, name, issuer)
    }
}

public inline fun CommandInvocation.rebuild(block: CommandInvocation.Builder.() -> Unit): CommandInvocation =
    toBuilder().apply(block).build()

/**
 * Runs the command invocations whose names it declares, in the chat's command lane beside its running turn.
 *
 * An invocation goes to the handler that declares its name, ignoring case, else to the skill of that name, else to
 * the model as the message that carried it.
 */
@ContributedSpi
public interface CommandHandler {
    /** The commands it claims, whose names no other handler of the runtime declares. */
    public val commands: List<CommandSpec>

    /** [command]'s description in [language], or in the handler's default language when it has none for it. */
    public fun description(command: String, language: LanguageTag?): String =
        commands.first { it.name == command }.description

    public suspend fun handle(invocation: CommandInvocation, context: CommandContext)
}

/** A command that a [CommandHandler] claims. */
@Poko
public class CommandSpec private constructor(
    public val name: String,
    public val description: String,
    /** Whether it runs at once beside the chat's other commands. */
    public val runsAlongside: Boolean,
    /**
     * Whether its arguments may carry credentials, such as a pasted sign-in address: the agent then tells the model
     * only that the command ran, never its arguments.
     */
    public val secretArguments: Boolean,
) {
    init {
        require(COMMAND_NAME.matches(name)) {
            "Malformed command name '$name': it must be lowercase letters, digits and '_', 32 characters at most, " +
                "such as \"new\"."
        }
        require(description.isNotBlank()) { "The description of command '$name' may not be blank." }
    }

    public fun toBuilder(): Builder =
        Builder(name, description).runsAlongside(runsAlongside).secretArguments(secretArguments)

    public class Builder internal constructor(private var name: String, private var description: String) {
        private var runsAlongside: Boolean = false
        private var secretArguments: Boolean = false

        public fun name(name: String): Builder = apply { this.name = name }

        public fun description(description: String): Builder = apply { this.description = description }

        public fun runsAlongside(runsAlongside: Boolean): Builder = apply { this.runsAlongside = runsAlongside }

        public fun secretArguments(secretArguments: Boolean): Builder = apply { this.secretArguments = secretArguments }

        public fun build(): CommandSpec = CommandSpec(name, description, runsAlongside, secretArguments)
    }

    public companion object {
        public fun builder(name: String, description: String): Builder = Builder(name, description)
    }
}

public inline fun CommandSpec.rebuild(block: CommandSpec.Builder.() -> Unit): CommandSpec =
    toBuilder().apply(block).build()

/** Where a [CommandHandler] runs one invocation. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface CommandContext {
    /** The invocation's turn, of kind COMMAND, whose actor is the issuer. */
    public val turn: TurnInfo

    /** Sends [text] to the chat as a notice in reply to the invocation, kept from the model when [forModel] is false. */
    public suspend fun reply(text: String, markup: Markup = Markup.PLAIN, forModel: Boolean = true): Delivery
}

private val COMMAND_NAME = Regex("[a-z0-9_]{1,32}")
