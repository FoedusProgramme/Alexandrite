package org.unitedsoftware.alexandrite.app.control

import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.common.pairing.PairedChat
import org.unitedsoftware.alexandrite.channel.common.pairing.PairingDecision
import org.unitedsoftware.alexandrite.channel.common.pairing.PairingDesk
import org.unitedsoftware.alexandrite.channel.common.pairing.PairingDesks
import org.unitedsoftware.alexandrite.channel.common.pairing.PairingRequest
import org.unitedsoftware.alexandrite.channel.common.pairing.Unpairing
import org.unitedsoftware.alexandrite.mcp.McpControl
import org.unitedsoftware.alexandrite.mcp.McpLoginException
import org.unitedsoftware.alexandrite.sdk.channel.ChannelControl
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.config.ConfigChanges
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditException
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal
import org.unitedsoftware.alexandrite.sdk.config.HostConfig
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.runtime.HostApi
import org.unitedsoftware.alexandrite.sdk.turn.AgentControl
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/** The pairing desks that the channels contribute. */
@HostApi
@Singleton
internal class ContributedDesks(all: List<PairingDesk>) {
    val desks = PairingDesks(all)
}

/** The configured channel instances and their states. */
@HostApi
@Singleton
internal class RunningInstances(private val directory: ChannelDirectory, private val channels: ChannelControl) {
    fun views(): List<InstanceView> = directory.instances.map {
        InstanceView("$it", "${channels.state(it) ?: "unknown"}")
    }
}

/** What the running app gives the control socket besides its pairing desks and agents. */
internal interface RunningApp {
    fun status(): ControlReply.Status

    /** Requests the app's stop and returns at once. */
    fun stop()

    val configFile: Path?

    /** The config file the app runs with, null when it has none to change. */
    fun config(): HostConfig?

    /** The MCP servers, null when the MCP plugin does not run. */
    fun mcp(): McpControl?
}

/** Answers the requests of the control socket of the app [version] over what [desks], [control] and [app] give. */
internal class ControlAnswers(
    private val version: String,
    private val control: () -> AgentControl? = { null },
    private val app: RunningApp? = null,
    private val desks: () -> PairingDesks,
) {
    suspend fun answer(request: ControlRequest): ControlReply = when (request) {
        ControlRequest.Ping -> ControlReply.Pong(version)

        ControlRequest.ListPairings -> withDesks { desks ->
            ControlReply.Pairings(desks.pending().map(::view), desks.paired().map(::view))
        }

        is ControlRequest.Approve -> withDesks { desks -> decided(desks.approve(request.code, by = null)) }

        is ControlRequest.Deny -> withDesks { desks -> decided(desks.deny(request.code, by = null)) }

        is ControlRequest.Unpair -> unpair(request.chat)

        ControlRequest.Status -> app?.status() ?: unanswered()

        ControlRequest.Stop -> app?.let {
            it.stop()
            ControlReply.Stopping
        } ?: unanswered()

        ControlRequest.Chats -> withDesks { desks -> ControlReply.Chats(desks.paired().map { chatView(it) }) }

        is ControlRequest.ChangeConfig -> changeConfig {
            edit {
                for (change in request.changes) {
                    if (change.remove) remove(change.path) else set(change.path, change.value)
                }
            }
        }

        is ControlRequest.ReplaceConfig -> changeConfig { replace(request.text) }

        ControlRequest.McpServers -> withMcp { mcp ->
            ControlReply.McpServers(
                mcp.servers().map {
                    McpServerView(it.name, it.transport, it.state, it.tools, it.lastError, it.widerScopes)
                },
            )
        }

        is ControlRequest.McpLogin -> withMcp { mcp ->
            val login = mcp.login(request.server)
            ControlReply.McpLoginStarted(request.server, login.url, login.loopback)
        }

        is ControlRequest.McpComplete -> withMcp { mcp ->
            mcp.completeLogin(request.server, request.address)
            ControlReply.McpLoggedIn(request.server)
        }

        is ControlRequest.McpLogout -> withMcp { mcp ->
            ControlReply.McpLoggedOut(request.server, mcp.logout(request.server))
        }
    }

    private suspend fun changeConfig(change: suspend HostConfig.() -> ConfigChanges): ControlReply {
        val config = app?.config()?.takeIf { app.configFile != null } ?: return ControlReply.ConfigRefused(
            ConfigEditRefusal.NO_FILE.id,
            "This Alexandrite runs without a config file to change.",
        )
        val path = "${app.configFile}"
        return try {
            val changes = config.change()
            ControlReply.ConfigChanged(path, changes.changed, changes.restart)
        } catch (e: ConfigEditException) {
            ControlReply.ConfigRefused(e.refusal.id, e.message ?: "$e")
        }
    }

    private suspend fun withMcp(answer: suspend (McpControl) -> ControlReply): ControlReply {
        val mcp = app?.mcp() ?: return ControlReply.Failure("The MCP plugin does not run in this Alexandrite.")
        return try {
            answer(mcp)
        } catch (e: McpLoginException) {
            ControlReply.Failure(e.message ?: "$e")
        }
    }

    /** [paired] and the agent that serves it. */
    private suspend fun chatView(paired: PairedChat): ChatView {
        val approval = paired.approval
        val settings = try {
            control()?.settings(paired.chat)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Cannot tell which agent serves the chat {}: {}", paired.chat, "$e")
            null
        }
        return ChatView(
            "${paired.chat}",
            approval?.kind?.id,
            approval?.title,
            settings?.agent?.toString(),
            settings?.home == true,
            (settings?.model ?: settings?.defaultModel)?.toString(),
        )
    }

    private suspend fun unpair(text: String): ControlReply {
        val chat = ChatAddress.parseOrNull(text)?.parent
            ?: return ControlReply.Failure("'$text' is no chat address, such as telegram:work:123456789.")
        return withDesks { desks ->
            when (val unpairing = desks.unpair(chat)) {
                Unpairing.NotPaired -> ControlReply.NotPaired("$chat")
                Unpairing.Unpaired -> unpaired(chat)
                is Unpairing.Declared -> ControlReply.Declared("$chat", unpairing.approvalRemoved)
                is Unpairing.Failed -> ControlReply.UnpairFailed("$chat", unpairing.reason)
                else -> unknown(unpairing)
            }
        }
    }

    /** The reply that [chat] is unpaired, naming the agents that still keep it as a home chat or linked. */
    private fun unpaired(chat: ChatAddress): ControlReply {
        val (homeOf, linkedAt) = try {
            val control = control() ?: return ControlReply.Unpaired("$chat")
            val homes = control.agents(chat.instance).filter { it.home?.parent == chat }.map { "${it.id}" }
            val links = control.links().filter { group -> (group.members + group.anchor).any { it.parent == chat } }
            homes to links.map { "${it.agent}" }.distinct()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Cannot tell whether the unpaired chat {} is still a home chat or linked: {}", chat, "$e")
            return ControlReply.Unpaired("$chat")
        }
        val uses = homeOf.map { "the home chat of agent $it" } + linkedAt.map { "linked at agent $it" }
        if (uses.isNotEmpty()) {
            logger.warn(
                "Chat {} is unpaired but still {}: the bot sends it nothing until it is paired again",
                chat,
                uses.joinToString(" and "),
            )
        }
        return ControlReply.Unpaired("$chat", homeOf, linkedAt)
    }

    private inline fun withDesks(answer: (PairingDesks) -> ControlReply): ControlReply {
        val desks = desks()
        return if (desks.all.isEmpty()) ControlReply.NoDesk else answer(desks)
    }
}

private fun unanswered(): ControlReply = ControlReply.Failure("This Alexandrite does not answer that request.")

private fun decided(decision: PairingDecision): ControlReply = when (decision) {
    is PairingDecision.Approved -> ControlReply.Approved(view(decision.request))
    is PairingDecision.Denied -> ControlReply.Denied(view(decision.request))
    is PairingDecision.Failed -> ControlReply.NotKept(view(decision.request), decision.reason)
    PairingDecision.Unknown -> ControlReply.UnknownCode
    PairingDecision.Malformed -> ControlReply.MalformedCode
    else -> unknown(decision)
}

private fun unknown(answer: Any): ControlReply =
    ControlReply.Failure("A pairing desk answered ${answer::class.simpleName}, which this Alexandrite does not know.")

private fun view(request: PairingRequest): RequestView = RequestView(
    "${request.chat}",
    request.kind.id,
    request.title,
    request.requester?.let(::view),
    request.code,
    request.expiresAt,
)

private fun view(paired: PairedChat): PairedView = PairedView(
    "${paired.chat}",
    paired.declared,
    paired.approval?.let { approval ->
        ApprovalView(
            approval.kind.id,
            approval.title,
            approval.requester?.let(::view),
            approval.approvedAt,
            approval.approvedBy?.toString(),
        )
    },
    paired.instanceRunning,
)

private fun view(user: ChatUser): RequesterView = RequesterView("${user.address}", user.displayName, user.username)

private val logger = LoggerFactory.getLogger(ControlAnswers::class.java)
