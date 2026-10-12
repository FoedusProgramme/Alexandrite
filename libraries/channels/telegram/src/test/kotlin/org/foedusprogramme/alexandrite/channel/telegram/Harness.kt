package org.foedusprogramme.alexandrite.channel.telegram

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.foedusprogramme.alexandrite.channel.telegram.fake.FakeBotApi
import org.foedusprogramme.alexandrite.channel.telegram.fake.FakeUser
import org.foedusprogramme.alexandrite.runtime.Termination
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.turn.CommandContext
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import org.foedusprogramme.alexandrite.sdk.turn.CommandSpec
import org.foedusprogramme.alexandrite.sdk.turn.Submission
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.RecordingAgentControl
import org.foedusprogramme.alexandrite.testkit.RecordingTurnSubmitter
import kotlin.time.Duration.Companion.seconds

const val PLUGIN = "alexandrite-channel-telegram"

val WORK = ChannelInstanceId(ChannelType("telegram"), "work")

/**
 * The Telegram plugin over a [FakeBotApi] whose bot has Ada as its admin, Bob as a member and a group, where every
 * accepted turn ends with [outcome] at once unless it is null and a handler claims the default commands.
 */
class TelegramHarness(outcome: TurnOutcome? = TurnOutcome.Completed(null)) : AutoCloseable {
    val fake = FakeBotApi()
    val submitter = RecordingTurnSubmitter(outcome)
    val control = RecordingAgentControl()
    val ada = FakeUser(ADA, "Ada", "Lovelace", "ada_test")
    val bob = FakeUser(BOB, "Bob", username = "bob_test")
    val adaChat = fake.direct(ada)
    val bobChat = fake.direct(bob)
    val group = fake.group(GROUP, "Backend")

    /** The config of an instance of the fake's bot, with [allowed] as its paired chats, plus the members [more]. */
    fun instance(allowed: String = "[$ADA, $BOB, $GROUP]", token: String = fake.token, more: String = ""): String =
        """{"botToken": "$token", "apiBaseUrl": "${fake.baseUrl}", "adminUserIds": [$ADA], """ +
            """"allowedChats": $allowed, "polling": {"timeoutSeconds": 1}$more}"""

    /** A harness of the plugin whose instances are [instances], by name. */
    fun builder(vararg instances: Pair<String, String> = arrayOf("work" to instance())): PluginHarness.Builder =
        PluginHarness.builder(AlexandriteChannelTelegramIndex())
            .config("""{"instances": {${instances.joinToString { (name, config) -> "\"$name\": $config" }}}}""")
            .turnSubmitter(submitter)
            .agentControl(control)
            .commandHandler(ClaimedCommands(*DEFAULT_COMMANDS))

    fun run(builder: PluginHarness.Builder = builder(), block: suspend PluginHarness.Running.() -> Unit): Termination =
        runBlocking { withTimeout(60.seconds) { builder.build().run(block) } }

    /** The first [count] submissions, once they were made. */
    suspend fun submitted(count: Int): List<Submission> = withTimeout(10.seconds) { submitter.awaitSubmissions(count) }

    /** Waits until the poller dispatched the first [count] updates and polled again. */
    fun consumed(count: Long) {
        fake.await { confirmedOffset.takeIf { it > count } }
    }

    fun address(chat: Long, thread: Long? = null): ChatAddress = ChatAddress(WORK, "$chat", thread?.toString())

    override fun close() {
        fake.close()
    }

    companion object {
        const val ADA = 1001L
        const val BOB = 1002L
        const val GROUP = -1001234500000L

        val DEFAULT_COMMANDS = arrayOf(
            "new", "cancel", "agent", "model", "mode", "reasoning", "language", "home", "link", "unlink", "settings",
            "trust", "untrust", "allow", "jobs", "compact", "context",
        )
    }
}

/** A handler that claims the commands [names] and is never asked to run them. */
class ClaimedCommands(vararg names: String) : CommandHandler {
    override val commands: List<CommandSpec> = names.map { CommandSpec.builder(it, "The test's /$it").build() }

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext): Unit =
        error("The test runs no command.")
}
