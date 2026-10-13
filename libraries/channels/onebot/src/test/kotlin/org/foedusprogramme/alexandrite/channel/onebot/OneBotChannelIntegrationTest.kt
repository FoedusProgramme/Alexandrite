package org.foedusprogramme.alexandrite.channel.onebot

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.turn.Submission
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.PluginHarness.Running
import org.foedusprogramme.alexandrite.testkit.RecordingTurnSubmitter
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The OneBot channel inside a real runtime.
 *
 * A harness builds the plugin set and the container, a peer built from the library serves the implementation, and the
 * test drives what a user would do: the implementation reports a message, the agent is asked for a turn, and the
 * answer the agent writes goes back out through the actions of the standard.
 */
class OneBotChannelIntegrationTest {
    private val instance = ChannelInstanceId(ChannelType("onebot"), "main")

    private val directory = key<ChannelDirectory>()

    @Test
    fun `a reported message reaches the agent and the answer goes back to the implementation`() {
        val peer = ImplementationPeer()
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelOnebotIndex())
                    .config(config(port))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(peer.awaitConnection(), "the channel did not connect to the implementation")
                        peer.report(privateMessage())
                        val submission = awaitSubmission(submitter)
                        val message = assertIs<Submission.Message>(submission)
                        assertEquals("private:12345678", message.message.chat.chat)
                        assertEquals("hello there", message.message.text)

                        val channel = org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory::class.java
                        val delivery = sendTo(message.message.chat)
                        assertEquals("7", assertIs<Delivery.Delivered>(delivery).messages.single().id)
                        val call = peer.awaitCall("send_private_msg")
                        assertTrue(call.contains("hello back"), "the implementation was not sent the reply: $call")
                    }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `a group message that names nobody reaches no agent`() {
        val peer = ImplementationPeer()
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelOnebotIndex())
                    .config(config(port))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(peer.awaitConnection(), "the channel did not connect to the implementation")
                        peer.report(groupMessage(text = "hello there"))
                        assertTrue(
                            awaitNoSubmission(submitter),
                            "the agent was asked for a turn of a message that names nobody",
                        )
                    }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `a group message that names the bot reaches the agent`() {
        val peer = ImplementationPeer()
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelOnebotIndex())
                    .config(config(port))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(peer.awaitConnection(), "the channel did not connect to the implementation")
                        peer.report(groupMessage(text = "hello there", at = "10001000"))
                        val message = assertIs<Submission.Message>(awaitSubmission(submitter))
                        assertEquals("hello there", message.message.text)
                    }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `a command in a group reaches the agent as a command without naming the bot`() {
        val peer = ImplementationPeer()
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelOnebotIndex())
                    .config(config(port))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(peer.awaitConnection(), "the channel did not connect to the implementation")
                        peer.report(groupMessage(text = "/model deepseek/one"))
                        val command = assertIs<Submission.Command>(awaitSubmission(submitter))
                        assertEquals("model", command.invocation.name)
                        assertEquals("deepseek/one", command.invocation.arguments)
                    }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `a group message answering a message the bot sent reaches the agent`() {
        val peer = ImplementationPeer()
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelOnebotIndex())
                    .config(config(port))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(peer.awaitConnection(), "the channel did not connect to the implementation")
                        val group = ChatAddress(instance, "group:987654")
                        assertIs<Delivery.Delivered>(sendTo(group))
                        peer.report(groupMessage(text = "hello there", reply = "8"))
                        assertTrue(
                            awaitNoSubmission(submitter),
                            "the agent was asked for a turn of a message answering somebody else",
                        )
                        peer.report(groupMessage(text = "hello there", reply = "7"))
                        val message = assertIs<Submission.Message>(awaitSubmission(submitter))
                        assertEquals("hello there", message.message.text)
                    }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `a chat this instance does not serve is offered its pairing code and reaches no agent`() {
        val peer = ImplementationPeer()
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelOnebotIndex())
                    .config(config(port))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(peer.awaitConnection(), "the channel did not connect to the implementation")
                        peer.report(unservedGroupMessage())
                        val said = peer.awaitCall("send_group_msg")
                        assertTrue(said.contains("not paired"), "the chat was not offered a code: $said")
                        assertTrue(
                            awaitNoSubmission(submitter),
                            "a message of a chat this instance does not serve reached the agent",
                        )
                    }
            }
        } finally {
            peer.stop()
        }
    }

    /** A group report of a chat that the config of the test does not declare. */
    private fun unservedGroupMessage(): String = """
        {
          "time": 1515204254,
          "self_id": 10001000,
          "post_type": "message",
          "message_type": "group",
          "sub_type": "normal",
          "message_id": 12,
          "group_id": 111111,
          "user_id": 12345678,
          "message": [
            {"type": "at", "data": {"qq": 10001000}},
            {"type": "text", "data": {"text": "hello there"}}
          ],
          "raw_message": "hello there",
          "sender": {"user_id": 12345678, "nickname": "someone", "role": "member"}
        }
    """.trimIndent()

    private suspend fun org.foedusprogramme.alexandrite.testkit.PluginHarness.Running.awaitSubmission(
        submitter: RecordingTurnSubmitter,
    ): Submission {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            submitter.submissions.firstOrNull()?.let { return it }
            kotlinx.coroutines.delay(20)
        }
        error("the agent was asked for no turn")
    }

    private suspend fun Running.sendTo(chat: ChatAddress): Delivery {
        val front = get(directory).channel(instance) ?: error("the instance has no channel")
        val message = OutboundMessage.builder("hello back", MessageKind.REPLY).build()
        return front.send(chat, message)
    }

    private fun config(port: Int): String = """
        {
          "settings": {"eventQueueCapacity": 64},
          "instances": {
            "main": {
              "transport": "ws",
              "endpoint": "ws://127.0.0.1:$port",
              "connectTimeoutMillis": 5000,
              "firstByteTimeoutMillis": 5000,
              "idleTimeoutMillis": 5000,
              "reconnectIntervalMillis": 200,
          "allowedChats": ["group:987654", "private:12345678"]
            }
          }
        }
    """.trimIndent()

    private fun privateMessage(): String = """
        {
          "time": 1515204254,
          "self_id": 10001000,
          "post_type": "message",
          "message_type": "private",
          "sub_type": "friend",
          "message_id": 12,
          "user_id": 12345678,
          "message": "hello there",
          "raw_message": "hello there",
          "sender": {"user_id": 12345678, "nickname": "someone"}
        }
    """.trimIndent()

    /** A group report of [text], whose message names [at] or answers [reply] when they are given. */
    private fun groupMessage(text: String, at: String? = null, reply: String? = null): String = buildString {
        append("""{"time":1515204254,"self_id":10001000,"post_type":"message","message_type":"group",""")
        append(""""sub_type":"normal","message_id":12,"group_id":987654,"user_id":12345678,"message":[""")
        at?.let { append("""{"type":"at","data":{"qq":$it}},""") }
        reply?.let { append("""{"type":"reply","data":{"id":$it}},""") }
        append("""{"type":"text","data":{"text":"$text"}}],""")
        append(""""raw_message":"$text",""")
        append(""""sender":{"user_id":12345678,"nickname":"someone","role":"member"}}""")
    }

    /** Whether the agent was asked for no turn within the time one would arrive in. */
    private suspend fun awaitNoSubmission(submitter: RecordingTurnSubmitter): Boolean {
        val deadline = System.nanoTime() + 500_000_000L
        while (System.nanoTime() < deadline) {
            if (submitter.submissions.isNotEmpty()) return false
            kotlinx.coroutines.delay(20)
        }
        return true
    }

    /** An implementation that serves the API and reports what the test tells it to. */
    private class ImplementationPeer : WebSocketServer(InetSocketAddress("127.0.0.1", 0)) {
        private val started = CountDownLatch(1)
        private val connected = CountDownLatch(1)
        private val calls = CopyOnWriteArrayList<String>()
        private val callWaiters = CopyOnWriteArrayList<Pair<String, CountDownLatch>>()

        @Volatile
        private var socket: WebSocket? = null

        override fun onStart() {
            started.countDown()
        }

        override fun onOpen(connection: WebSocket, handshake: ClientHandshake) {
            socket = connection
            connected.countDown()
        }

        override fun onMessage(connection: WebSocket, message: String) {
            calls += message
            val echo = message.substringAfter("\"echo\":\"", "").substringBefore('"')
            if (echo.isEmpty()) return
            connection.send(
                """{"status":"ok","retcode":0,"data":{"message_id":7},"echo":"$echo"}""",
            )
        }

        override fun onClose(connection: WebSocket, code: Int, reason: String, remote: Boolean) {
            if (socket === connection) socket = null
        }

        override fun onError(connection: WebSocket?, ex: Exception) = Unit

        /** Starts listening and returns the port this peer took. */
        fun startAndPort(): Int {
            start()
            check(started.await(5, TimeUnit.SECONDS)) { "the peer did not start" }
            return port
        }

        /** Reports [json] as an event of the implementation. */
        fun report(json: String) {
            val connection = socket ?: error("nothing is connected")
            connection.send(json)
        }

        /** Waits for the channel to connect and returns whether it did. */
        fun awaitConnection(): Boolean = connected.await(5, TimeUnit.SECONDS)

        /** Waits for a call of [action] and returns it, whether it arrived before or after this call. */
        fun awaitCall(action: String): String {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                calls.lastOrNull { it.contains("\"$action\"") }?.let { return it }
                Thread.sleep(20)
            }
            error("no call of $action arrived, saw $calls")
        }
    }
}
