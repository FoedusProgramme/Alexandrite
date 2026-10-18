package org.unitedsoftware.alexandrite.channel.telegram.fake

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A Bot API server on the loopback interface for the bot of [token], which keeps chats, messages and updates as
 * Telegram does.
 *
 * Group messages reach the bot only as privacy mode lets them through unless [canReadAllGroupMessages].
 */
public class FakeBotApi(
    public val token: String = FAKE_BOT_TOKEN,
    public val username: String = "alexandrite_test_bot",
    public val canReadAllGroupMessages: Boolean = true,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val stopping = CountDownLatch(1)
    private var closed = false
    private val chats = HashMap<Long, FakeChat>()

    /** The users who wrote in each chat, by chat and user id. */
    private val senders = HashMap<Long, MutableMap<Long, JsonObject>>()

    /** The users that `getChatMember` tells are administrators of their chats. */
    public val chatAdmins: MutableSet<Long> = mutableSetOf()
    private val topics = HashMap<Pair<Long, Long>, FakeTopic>()
    private val messages = HashMap<Pair<Long, Long>, JsonObject>()
    private val sent = LinkedHashMap<Pair<Long, Long>, FakeBotMessage>()
    private val lastMessageIds = HashMap<Long, Long>()
    private val updates = ArrayDeque<JsonObject>()
    private var lastUpdateId = 0L
    private var lastQueryId = 0L
    private val openQueries = HashSet<String>()
    private val answered = mutableListOf<FakeAnswer>()
    private var confirmed = 0L
    private val polledOffsets = mutableListOf<Long?>()
    private val received = mutableListOf<FakeCall>()
    private val scripts = HashMap<String, ArrayDeque<Step>>()
    private val files = HashMap<String, FakeFile>()

    /** The command menus the bot set, by scope and language code. */
    private val menus = HashMap<Pair<FakeScope, String>, Map<String, String>>()
    private var lastFileId = 0L

    /** The file part of the last form received for each method. */
    private val uploads = HashMap<String, FakeForm.Upload>()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), 0)

    public val botId: Long = token.substringBefore(':').toLong()

    public val bot: FakeUser = FakeUser(botId, "Alexandrite Test Bot", username = username, isBot = true)

    /** The URL that `/bot<token>/<method>` follows. */
    public val baseUrl: String

    /** The webhook that `getWebhookInfo` reports, empty for none. */
    @Volatile
    public var webhookUrl: String = ""

    init {
        server.executor = executor
        server.createContext("/") { serve(it) }
        server.start()
        baseUrl = "http://$LOOPBACK:${server.address.port}"
    }

    /** The direct chat of [user] and the bot. */
    public fun direct(user: FakeUser): FakeChat =
        register(FakeChat(user.id, "private", null, user, null, isForum = false))

    /** A group with the negative [id], a supergroup unless [supergroup] is false. */
    public fun group(
        id: Long,
        title: String,
        supergroup: Boolean = true,
        forum: Boolean = false,
        username: String? = null,
    ): FakeChat {
        require(id < 0) { "A group id is negative." }
        require(supergroup || !forum) { "Only a supergroup is a forum." }
        return register(FakeChat(id, if (supergroup) "supergroup" else "group", title, null, username, forum))
    }

    /** A new topic of the forum [chat] that [creator] opens, which reaches the bot as no update. */
    public fun topic(chat: FakeChat, name: String, creator: FakeUser): FakeTopic = lock.withLock {
        require(chat.isForum) { "Chat ${chat.id} is no forum." }
        val id = nextMessageId(chat.id)
        val root = buildJsonObject {
            put("message_id", id)
            put("from", creator.json())
            put("chat", chat.json())
            put("date", now())
            put("message_thread_id", id)
            put("is_topic_message", true)
            putJsonObject("forum_topic_created") {
                put("name", name)
                put("icon_color", TOPIC_COLOR)
            }
        }
        messages[chat.id to id] = root
        FakeTopic(chat, id, name).also { topics[chat.id to id] = it }
    }

    /**
     * Has [from] write [text] in [chat], or in its [topic], replying to the message [replyTo], with the entities that
     * Telegram finds unless [entities] are given, and returns the message.
     */
    public fun send(
        from: FakeUser,
        chat: FakeChat,
        text: String,
        topic: FakeTopic? = null,
        replyTo: Long? = null,
        entities: JsonArray? = null,
    ): JsonObject = lock.withLock {
        require(chats[chat.id] === chat) { "Chat ${chat.id} is not this fake's." }
        require(topic == null || topic.chat === chat) { "Topic ${topic?.threadId} is not in chat ${chat.id}." }
        val target = replyTo?.let { requireNotNull(messages[chat.id to it]) { "No message $it in chat ${chat.id}." } }
        val found = entities ?: telegramEntities(text)
        val id = nextMessageId(chat.id)
        val message = buildJsonObject {
            put("message_id", id)
            put("from", from.json())
            put("chat", chat.json())
            put("date", now())
            topic?.let {
                put("message_thread_id", it.threadId)
                put("is_topic_message", true)
            }
            (target ?: topic?.let { messages.getValue(chat.id to it.threadId) })?.let {
                put("reply_to_message", JsonObject(it - "reply_to_message"))
            }
            put("text", text)
            if (found.isNotEmpty()) put("entities", found)
        }
        messages[chat.id to id] = message
        senders.getOrPut(chat.id, ::HashMap)[from.id] = from.json()
        if (!chat.isGroup || canReadAllGroupMessages || passesPrivacy(text, found, target)) queueUpdate(message)
        message
    }

    /**
     * Keeps [bytes] as a file that the bot may download, of [size] bytes as Telegram reports it, and returns its
     * `file_id`.
     */
    public fun file(bytes: ByteArray, size: Long = bytes.size.toLong()): String = lock.withLock {
        val id = "file-${++lastFileId}"
        files[id] = FakeFile(bytes, size, "documents/$id.bin")
        id
    }

    /**
     * Has [from] send the document [name] of [mediaType] holding [bytes] in [chat] with [caption], in the album
     * [mediaGroup] unless it is null, and returns the message.
     */
    public fun sendDocument(
        from: FakeUser,
        chat: FakeChat,
        name: String,
        bytes: ByteArray,
        mediaType: String = "application/octet-stream",
        caption: String? = null,
        mediaGroup: String? = null,
    ): JsonObject {
        val id = file(bytes)
        val document = buildJsonObject {
            put("file_id", id)
            put("file_unique_id", "unique-$id")
            put("file_name", name)
            put("mime_type", mediaType)
            put("file_size", bytes.size)
        }
        return sendMedia(from, chat, "document", document, caption, mediaGroup)
    }

    /** Has [from] send a photo of [bytes] in [chat] with [caption], in the album [mediaGroup] unless it is null. */
    public fun sendPhoto(
        from: FakeUser,
        chat: FakeChat,
        bytes: ByteArray,
        caption: String? = null,
        mediaGroup: String? = null,
    ): JsonObject {
        val id = file(bytes)
        val sizes = buildJsonArray {
            add(
                buildJsonObject {
                    put("file_id", id)
                    put("file_unique_id", "unique-$id")
                    put("width", PHOTO_SIDE)
                    put("height", PHOTO_SIDE)
                    put("file_size", bytes.size)
                },
            )
        }
        return sendMedia(from, chat, "photo", sizes, caption, mediaGroup)
    }

    private fun sendMedia(
        from: FakeUser,
        chat: FakeChat,
        field: String,
        media: JsonElement,
        caption: String?,
        mediaGroup: String?,
    ): JsonObject = queue(
        buildJsonObject {
            put("from", from.json())
            put("chat", chat.json())
            put(field, media)
            caption?.let {
                put("caption", it)
                val found = telegramEntities(it)
                if (found.isNotEmpty()) put("caption_entities", found)
            }
            mediaGroup?.let { put("media_group_id", it) }
        },
    )

    /**
     * Has [from] press the button [button] of the bot's [message] as it read when the test took it, and returns the
     * id of the callback query that the bot receives.
     */
    public fun press(from: FakeUser, message: FakeBotMessage, button: String): String = lock.withLock {
        val pressed = requireNotNull(message.buttons.flatten().firstOrNull { it.text == button }) {
            "Message ${message.id} in chat ${message.chat} has no button '$button'."
        }
        val current = messages.getValue(message.chat to message.id)
        val id = "query-${++lastQueryId}"
        openQueries += id
        updates += buildJsonObject {
            put("update_id", ++lastUpdateId)
            putJsonObject("callback_query") {
                put("id", id)
                put("from", from.json())
                put("message", current)
                put("chat_instance", "instance-${message.chat}")
                put("data", pressed.data)
            }
        }
        changed.signalAll()
        id
    }

    /** What the bot answered to the button presses, in order. */
    public val answers: List<FakeAnswer> get() = lock.withLock { answered.toList() }

    /** Queues an update of [message] as given, which gets a message id and a date when it has none. */
    public fun queue(message: JsonObject): JsonObject = lock.withLock {
        val chat = requireNotNull(message["chat"]?.jsonObject?.long("id")) { "The message names no chat." }
        val missing = buildMap {
            if ("message_id" !in message) put("message_id", JsonPrimitive(nextMessageId(chat)))
            if ("date" !in message) put("date", JsonPrimitive(now()))
        }
        val complete = JsonObject(message + missing)
        messages[chat to complete.long("message_id")!!] = complete
        queueUpdate(complete)
        complete
    }

    /** The calls received of [method], or of every method. */
    public fun calls(method: String? = null): List<FakeCall> =
        lock.withLock { received.filter { method == null || it.method == method } }

    /** The `offset` of each `getUpdates`, in order. */
    public val offsets: List<Long?> get() = lock.withLock { polledOffsets.toList() }

    /** The offset below which every update is confirmed. */
    public val confirmedOffset: Long get() = lock.withLock { confirmed }

    /** The updates not yet confirmed. */
    public val pendingUpdates: Int get() = lock.withLock { updates.size }

    /** The messages the bot sent in [chat], or in every chat. */
    public fun botMessages(chat: Long? = null): List<FakeBotMessage> =
        lock.withLock { sent.values.filter { chat == null || it.chat == chat } }

    public fun botMessage(chat: Long, id: Long): FakeBotMessage? = lock.withLock { sent[chat to id] }

    /**
     * The command menu the bot set for the scope of [type], [chat] and [user] in [language], empty for every language:
     * the description of each command, in order; null when it set none.
     */
    public fun menu(type: String, chat: Long? = null, user: Long? = null, language: String = ""): Map<String, String>? =
        lock.withLock { menus[FakeScope(type, chat, user) to language] }

    /** The scopes and languages of the command menus the bot set. */
    public val menuKeys: Set<Pair<FakeScope, String>> get() = lock.withLock { menus.keys.toSet() }

    /** Answers the [nth] call of [method] with a failure of [status] carrying [description]. */
    public fun fail(
        method: String,
        status: Int,
        description: String,
        retryAfter: Int? = null,
        migrateTo: Long? = null,
        nth: Int = 1,
    ) {
        repeat(nth - 1) { script(method, Step.Pass) }
        val body = error(status, description) {
            if (retryAfter != null || migrateTo != null) {
                putJsonObject("parameters") {
                    retryAfter?.let { put("retry_after", it) }
                    migrateTo?.let { put("migrate_to_chat_id", it) }
                }
            }
        }
        script(method, Step.Answer(status, body.toString(), JSON_HEADERS))
    }

    /** Answers the next call of [method] with [body] as it is. */
    public fun respond(method: String, status: Int, body: String, headers: Map<String, String> = JSON_HEADERS) {
        script(method, Step.Answer(status, body, headers))
    }

    /** Breaks the connection of the next call of [method] in the middle of its answer. */
    public fun breakConnection(method: String) {
        script(method, Step.Break)
    }

    /** Holds the answer to the next call of [method] back for [duration]. */
    public fun delay(method: String, duration: Duration) {
        script(method, Step.Delay(duration))
    }

    /** What [probe] finds once it finds something, waiting up to [timeout] for the fake's state to get there. */
    public fun <T : Any> await(timeout: Duration = 5.seconds, probe: FakeBotApi.() -> T?): T {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        lock.withLock {
            while (true) {
                probe()?.let { return it }
                val left = -deadline.elapsedNow()
                if (!left.isPositive()) throw AssertionError("The fake Bot API did not get there within $timeout.")
                changed.awaitNanos(left.inWholeNanoseconds)
            }
        }
    }

    override fun close() {
        lock.withLock {
            closed = true
            changed.signalAll()
        }
        stopping.countDown()
        server.stop(0)
        executor.shutdownNow()
    }

    private fun register(chat: FakeChat): FakeChat = lock.withLock {
        require(chat.id !in chats) { "Chat ${chat.id} exists already." }
        chats[chat.id] = chat
        chat
    }

    private fun passesPrivacy(text: String, entities: JsonArray, target: JsonObject?): Boolean {
        val first = entities.firstOrNull()?.jsonObject
        if (first?.string("type") == "bot_command" && first.long("offset") == 0L) {
            val command = text.substring(0, first.long("length")!!.toInt())
            val addressee = command.substringAfter('@', "")
            if (addressee.isEmpty() || addressee.equals(username, ignoreCase = true)) return true
        }
        return target?.get("from")?.jsonObject?.long("id") == botId
    }

    private fun queueUpdate(message: JsonObject) {
        updates += buildJsonObject {
            put("update_id", ++lastUpdateId)
            put("message", message)
        }
        changed.signalAll()
    }

    private fun nextMessageId(chat: Long): Long = ((lastMessageIds[chat] ?: 0L) + 1).also { lastMessageIds[chat] = it }

    private fun now(): Long = clock.instant().epochSecond

    private fun script(method: String, step: Step) {
        lock.withLock { scripts.getOrPut(method) { ArrayDeque() } += step }
    }

    private fun serve(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path
            val download = FILE_PATH.matchEntire(path)
            val match = download ?: PATH.matchEntire(path)
            if (match == null) {
                write(exchange, 404, error(404, "Not Found").toString())
                return
            }
            val given = match.groupValues[1]
            val method = if (download != null) DOWNLOAD else match.groupValues[2]
            val body = exchange.requestBody.readAllBytes()
            val form = exchange.requestHeaders.getFirst("content-type")?.let { FakeForm.parse(it, body) }
            val params = when {
                download != null -> buildJsonObject { put("path", download.groupValues[2]) }
                form != null -> form.params
                else -> params(body.toString(Charsets.UTF_8))
            }
            val step = lock.withLock {
                form?.upload?.let { uploads[method] = it }
                received += FakeCall(method, params ?: JsonObject(emptyMap()))
                changed.signalAll()
                if (given == token) scripts[method]?.removeFirstOrNull() else null
            }
            when {
                given != token -> write(exchange, 401, error(401, "Unauthorized").toString())
                step is Step.Answer -> write(exchange, step.status, step.body, step.headers)
                step is Step.Break -> breakOff(exchange)
                step is Step.Delay -> if (!closesWithin(step.duration)) answer(exchange, method, params)
                else -> answer(exchange, method, params)
            }
        } catch (e: IOException) {
            // The client went away.
        } finally {
            try {
                exchange.close()
            } catch (e: IOException) {
                // A broken answer ends here.
            }
        }
    }

    private fun closesWithin(duration: Duration): Boolean =
        stopping.await(duration.inWholeMilliseconds, TimeUnit.MILLISECONDS)

    private fun answer(exchange: HttpExchange, method: String, params: JsonObject?) {
        if (method == DOWNLOAD) {
            download(exchange, params?.string("path").orEmpty())
            return
        }
        val (status, body) = when {
            params == null -> 400 to error(400, "Bad Request: the body is no JSON object")
            method == "getMe" -> 200 to ok(getMe())
            method == "getWebhookInfo" -> 200 to ok(webhookInfo())
            method == "getUpdates" -> 200 to ok(getUpdates(params))
            method == "sendMessage" -> sendMessage(params)
            method == "sendPhoto" || method == "sendDocument" -> sendFile(method, params)
            method == "getFile" -> getFile(params)
            method == "editMessageText" -> editMessageText(params)
            method == "editMessageReplyMarkup" -> editMessageReplyMarkup(params)
            method == "deleteMessage" -> deleteMessage(params)
            method == "answerCallbackQuery" -> answerCallbackQuery(params)
            method == "sendChatAction" -> sendChatAction(params)
            method == "setMyCommands" -> setMyCommands(params)
            method == "deleteMyCommands" -> deleteMyCommands(params)
            method == "setMessageReaction" || method == "pinChatMessage" -> onMessage(params)
            method == "setChatTitle" -> setChatTitle(params)
            method == "sendPoll" || method == "sendSticker" -> sendContent(method, params)
            method == "getChatMember" -> getChatMember(params)
            else -> 404 to error(404, "Not Found")
        }
        write(exchange, status, body.toString())
    }

    private fun getMe(): JsonObject = JsonObject(
        bot.json() + mapOf(
            "can_join_groups" to JsonPrimitive(true),
            "can_read_all_group_messages" to JsonPrimitive(canReadAllGroupMessages),
            "supports_inline_queries" to JsonPrimitive(false),
        ),
    )

    private fun webhookInfo(): JsonObject = buildJsonObject {
        put("url", webhookUrl)
        put("has_custom_certificate", false)
        put("pending_update_count", pendingUpdates)
    }

    private fun getUpdates(params: JsonObject): JsonArray = lock.withLock {
        val offset = params.long("offset")
        val limit = (params.int("limit") ?: MAX_UPDATES).coerceIn(1, MAX_UPDATES)
        val deadline = TimeSource.Monotonic.markNow() + (params.int("timeout") ?: 0).seconds
        polledOffsets += offset
        if (offset != null) {
            confirmed = maxOf(confirmed, offset)
            updates.removeAll { it.long("update_id")!! < offset }
        }
        changed.signalAll()
        while (updates.isEmpty() && !closed) {
            val left = -deadline.elapsedNow()
            if (!left.isPositive()) break
            changed.awaitNanos(left.inWholeNanoseconds)
        }
        JsonArray(updates.take(limit))
    }

    private fun sendMessage(params: JsonObject): Pair<Int, JsonObject> = lock.withLock {
        val chat = params.long("chat_id")?.let(chats::get) ?: return 400 to error(400, CHAT_NOT_FOUND)
        val thread = params.long("message_thread_id")
        if (thread != null && (chat.id to thread) !in topics) return 400 to error(400, THREAD_NOT_FOUND)
        val text = params.string("text").orEmpty()
        val entities = params["entities"] as? JsonArray ?: JsonArray(emptyList())
        textProblem(text, entities)?.let { return 400 to error(400, it) }
        val reply = params["reply_parameters"] as? JsonObject
        val target = reply?.long("message_id")?.let { messages[chat.id to it] }
        if (reply != null && target == null && reply["allow_sending_without_reply"]?.boolean() != true) {
            return 400 to error(400, "Bad Request: message to be replied not found")
        }
        val markup = params["reply_markup"] as? JsonObject
        val buttons = buttons(markup) ?: return 400 to error(400, BAD_MARKUP)
        val id = nextMessageId(chat.id)
        val message = buildJsonObject {
            put("message_id", id)
            put("from", bot.json())
            put("chat", chat.json())
            put("date", now())
            thread?.let {
                put("message_thread_id", it)
                put("is_topic_message", true)
            }
            (target ?: thread?.let { messages.getValue(chat.id to it) })?.let {
                put("reply_to_message", JsonObject(it - "reply_to_message"))
            }
            put("text", text)
            if (entities.isNotEmpty()) put("entities", entities)
            if (buttons.isNotEmpty()) put("reply_markup", markup!!)
        }
        messages[chat.id to id] = message
        val replyTo = target?.long("message_id")
        sent[chat.id to id] = FakeBotMessage(chat.id, id, text, entities, 0, replyTo, thread, buttons)
        changed.signalAll()
        200 to ok(message)
    }

    private fun sendFile(method: String, params: JsonObject): Pair<Int, JsonObject> = lock.withLock {
        val upload = uploads.remove(method) ?: return 400 to error(400, "Bad Request: there is no file in the request")
        val chat = params.long("chat_id")?.let(chats::get) ?: return 400 to error(400, CHAT_NOT_FOUND)
        val thread = params.long("message_thread_id")
        if (thread != null && (chat.id to thread) !in topics) return 400 to error(400, THREAD_NOT_FOUND)
        if (upload.bytes.isEmpty()) return 400 to error(400, "Bad Request: file must be non-empty")
        val caption = params.string("caption").orEmpty()
        val entities = params.string("caption_entities")?.let { Json.parseToJsonElement(it) as? JsonArray }
            ?: JsonArray(emptyList())
        if (caption.length > MAX_CAPTION) return 400 to error(400, "Bad Request: message caption is too long")
        if (caption.isNotEmpty()) textProblem(caption, entities)?.let { return 400 to error(400, it) }
        val id = nextMessageId(chat.id)
        val file = FakeSentFile(method, upload.fileName, upload.mediaType, upload.bytes)
        val message = buildJsonObject {
            put("message_id", id)
            put("from", bot.json())
            put("chat", chat.json())
            put("date", now())
            thread?.let {
                put("message_thread_id", it)
                put("is_topic_message", true)
            }
            val kept = file(upload.bytes)
            if (method == "sendPhoto") {
                put("photo", buildJsonArray { add(buildJsonObject { put("file_id", kept) }) })
            } else {
                put(
                    "document",
                    buildJsonObject {
                        put("file_id", kept)
                        put("file_name", upload.fileName)
                    },
                )
            }
            if (caption.isNotEmpty()) put("caption", caption)
            if (entities.isNotEmpty()) put("caption_entities", entities)
        }
        messages[chat.id to id] = message
        sent[chat.id to id] = FakeBotMessage(chat.id, id, caption, entities, 0, null, thread, file = file)
        changed.signalAll()
        200 to ok(message)
    }

    private fun getFile(params: JsonObject): Pair<Int, JsonObject> = lock.withLock {
        val id = params.string("file_id")
        val file = id?.let(files::get) ?: return 400 to error(400, "Bad Request: invalid file_id")
        if (file.size > MAX_DOWNLOAD) return 400 to error(400, "Bad Request: file is too big")
        200 to ok(
            buildJsonObject {
                put("file_id", id)
                put("file_unique_id", "unique-$id")
                put("file_size", file.size)
                put("file_path", file.path)
            },
        )
    }

    private fun download(exchange: HttpExchange, path: String) {
        val file = lock.withLock { files.values.firstOrNull { it.path == path } }
        if (file == null) {
            write(exchange, 404, error(404, "Not Found").toString())
            return
        }
        exchange.responseHeaders.add("content-type", "application/octet-stream")
        exchange.sendResponseHeaders(200, if (file.bytes.isEmpty()) -1 else file.bytes.size.toLong())
        if (file.bytes.isNotEmpty()) exchange.responseBody.write(file.bytes)
    }

    private fun editMessageText(params: JsonObject): Pair<Int, JsonObject> = lock.withLock {
        val chat = params.long("chat_id")?.let(chats::get) ?: return 400 to error(400, CHAT_NOT_FOUND)
        val key = chat.id to (params.long("message_id") ?: -1L)
        val message = messages[key] ?: return 400 to error(400, "Bad Request: message to edit not found")
        val current = sent[key] ?: return 400 to error(400, "Bad Request: message can't be edited")
        val text = params.string("text").orEmpty()
        val entities = params["entities"] as? JsonArray ?: JsonArray(emptyList())
        textProblem(text, entities)?.let { return 400 to error(400, it) }
        val markup = params["reply_markup"] as? JsonObject
        val buttons = buttons(markup) ?: return 400 to error(400, BAD_MARKUP)
        if (text == current.text && entities == current.entities && buttons == current.buttons) {
            return 400 to error(400, NOT_MODIFIED)
        }
        val edited = JsonObject(
            message - "entities" - "reply_markup" +
                mapOf("text" to JsonPrimitive(text), "edit_date" to JsonPrimitive(now())) +
                listOfNotNull(
                    ("entities" to entities).takeIf { entities.isNotEmpty() },
                    markup?.let { "reply_markup" to it }.takeIf { buttons.isNotEmpty() },
                ),
        )
        messages[key] = edited
        val edits = current.edits + 1
        sent[key] = FakeBotMessage(chat.id, current.id, text, entities, edits, current.replyTo, current.thread, buttons)
        changed.signalAll()
        200 to ok(edited)
    }

    private fun editMessageReplyMarkup(params: JsonObject): Pair<Int, JsonObject> = lock.withLock {
        val chat = params.long("chat_id")?.let(chats::get) ?: return 400 to error(400, CHAT_NOT_FOUND)
        val key = chat.id to (params.long("message_id") ?: -1L)
        val message = messages[key] ?: return 400 to error(400, "Bad Request: message to edit not found")
        val current = sent[key] ?: return 400 to error(400, "Bad Request: message can't be edited")
        val markup = params["reply_markup"] as? JsonObject
        val buttons = buttons(markup) ?: return 400 to error(400, BAD_MARKUP)
        if (buttons == current.buttons) return 400 to error(400, NOT_MODIFIED)
        val edited = JsonObject(
            message - "reply_markup" + mapOf("edit_date" to JsonPrimitive(now())) +
                listOfNotNull(markup?.let { "reply_markup" to it }.takeIf { buttons.isNotEmpty() }),
        )
        messages[key] = edited
        sent[key] = FakeBotMessage(
            chat.id,
            current.id,
            current.text,
            current.entities,
            current.edits + 1,
            current.replyTo,
            current.thread,
            buttons,
        )
        changed.signalAll()
        200 to ok(edited)
    }

    /** A reaction to or a pin of a message of the chat, which must exist. */
    private fun onMessage(params: JsonObject): Pair<Int, JsonObject> = lock.withLock {
        val chat = params.long("chat_id")?.let(chats::get) ?: return 400 to error(400, CHAT_NOT_FOUND)
        if (chat.id to (params.long("message_id") ?: -1L) !in messages) {
            return 400 to error(400, "Bad Request: message not found")
        }
        200 to ok(JsonPrimitive(true))
    }

    private fun setChatTitle(params: JsonObject): Pair<Int, JsonObject> = lock.withLock {
        val chat = params.long("chat_id")?.let(chats::get) ?: return 400 to error(400, CHAT_NOT_FOUND)
        if (!chat.isGroup) return 400 to error(400, "Bad Request: chat is not a group")
        if (params.string("title").isNullOrBlank()) return 400 to error(400, "Bad Request: title is empty")
        200 to ok(JsonPrimitive(true))
    }

    /** A poll or a sticker the bot sends, kept as a message that reads the poll's question or nothing. */
    private fun sendContent(method: String, params: JsonObject): Pair<Int, JsonObject> = lock.withLock {
        val chat = params.long("chat_id")?.let(chats::get) ?: return 400 to error(400, CHAT_NOT_FOUND)
        val thread = params.long("message_thread_id")
        val id = nextMessageId(chat.id)
        val question = params.string("question")
        val message = buildJsonObject {
            put("message_id", id)
            put("from", bot.json())
            put("chat", chat.json())
            put("date", now())
            thread?.let { put("message_thread_id", it) }
            if (method == "sendPoll") {
                putJsonObject("poll") { put("question", question.orEmpty()) }
            } else {
                putJsonObject("sticker") { put("file_id", params.string("sticker").orEmpty()) }
            }
        }
        messages[chat.id to id] = message
        sent[chat.id to id] = FakeBotMessage(chat.id, id, question.orEmpty(), JsonArray(emptyList()), 0, null, thread)
        changed.signalAll()
        200 to ok(message)
    }

    /** A member of the chat: someone who wrote in it, an administrator when [chatAdmins] names them. */
    private fun getChatMember(params: JsonObject): Pair<Int, JsonObject> = lock.withLock {
        val chat = params.long("chat_id")?.let(chats::get) ?: return 400 to error(400, CHAT_NOT_FOUND)
        val user = params.long("user_id")?.let { senders[chat.id]?.get(it) }
            ?: return 400 to error(400, "Bad Request: user not found")
        val status = if (user.long("id") in chatAdmins) "administrator" else "member"
        200 to ok(
            buildJsonObject {
                put("status", status)
                put("user", user)
            },
        )
    }

    private fun deleteMessage(params: JsonObject): Pair<Int, JsonObject> = lock.withLock {
        val chat = params.long("chat_id")?.let(chats::get) ?: return 400 to error(400, CHAT_NOT_FOUND)
        val key = chat.id to (params.long("message_id") ?: -1L)
        if (messages.remove(key) == null) return 400 to error(400, "Bad Request: message to delete not found")
        sent.remove(key)
        changed.signalAll()
        200 to ok(JsonPrimitive(true))
    }

    private fun answerCallbackQuery(params: JsonObject): Pair<Int, JsonObject> = lock.withLock {
        val query = params.string("callback_query_id")
        if (query == null || !openQueries.remove(query)) {
            return 400 to error(400, QUERY_INVALID)
        }
        answered += FakeAnswer(query, params.string("text"))
        changed.signalAll()
        200 to ok(JsonPrimitive(true))
    }

    /** The rows of the inline keyboard [markup] holds, empty for none and null when it does not read. */
    private fun buttons(markup: JsonObject?): List<List<FakeButton>>? {
        val rows = markup?.get("inline_keyboard") ?: return emptyList()
        return (rows as? JsonArray)?.map { row ->
            (row as? JsonArray)?.map { button ->
                val fields = button as? JsonObject ?: return null
                FakeButton(fields.string("text") ?: return null, fields.string("callback_data") ?: return null)
            } ?: return null
        }?.filter { it.isNotEmpty() }
    }

    private fun sendChatAction(params: JsonObject): Pair<Int, JsonObject> = lock.withLock {
        val chat = params.long("chat_id")?.let(chats::get) ?: return 400 to error(400, CHAT_NOT_FOUND)
        val thread = params.long("message_thread_id")
        if (thread != null && (chat.id to thread) !in topics) return 400 to error(400, THREAD_NOT_FOUND)
        if (params.string("action").isNullOrEmpty()) return 400 to error(400, "Bad Request: wrong parameter action")
        200 to ok(JsonPrimitive(true))
    }

    private fun setMyCommands(params: JsonObject): Pair<Int, JsonObject> = lock.withLock {
        val (key, problem) = menuKey(params)
        if (key == null) return 400 to error(400, problem.orEmpty())
        val commands = params["commands"] as? JsonArray
            ?: return 400 to error(400, "Bad Request: field \"commands\" must be an array")
        if (commands.size > MAX_COMMANDS) return 400 to error(400, "Bad Request: too many commands")
        val menu = LinkedHashMap<String, String>()
        for (command in commands) {
            val fields = command as? JsonObject ?: return 400 to error(400, BAD_COMMAND)
            val name = fields.string("command")
            val description = fields.string("description")
            if (name == null || !COMMAND.matches(name) || name in menu) return 400 to error(400, BAD_COMMAND)
            if (description.isNullOrBlank() || description.length > MAX_DESCRIPTION) {
                return 400 to error(400, "Bad Request: BOT_COMMAND_DESCRIPTION_INVALID")
            }
            menu[name] = description
        }
        if (menu.isEmpty()) menus.remove(key) else menus[key] = menu
        changed.signalAll()
        200 to ok(JsonPrimitive(true))
    }

    private fun deleteMyCommands(params: JsonObject): Pair<Int, JsonObject> = lock.withLock {
        val (key, problem) = menuKey(params)
        if (key == null) return 400 to error(400, problem.orEmpty())
        menus.remove(key)
        changed.signalAll()
        200 to ok(JsonPrimitive(true))
    }

    /** The menu that [params] name, else null and what is wrong with them. */
    private fun menuKey(params: JsonObject): Pair<Pair<FakeScope, String>?, String?> {
        val scope = params["scope"] as? JsonObject ?: buildJsonObject { put("type", "default") }
        val type = scope.string("type")?.takeIf { it in SCOPES } ?: return null to "Bad Request: unsupported scope type"
        val chat = scope.long("chat_id")
        val user = scope.long("user_id")
        val language = params.string("language_code").orEmpty()
        val problem = when {
            type in CHAT_SCOPES && (chat == null || !chats.containsKey(chat)) -> CHAT_NOT_FOUND
            type == "chat_member" && user == null -> "Bad Request: user not found"
            language.isNotEmpty() && !LANGUAGE.matches(language) -> "Bad Request: invalid language code specified"
            else -> null
        }
        return if (problem == null) (FakeScope(type, chat, user) to language) to null else null to problem
    }

    private fun textProblem(text: String, entities: JsonArray): String? {
        if (text.isEmpty()) return "Bad Request: message text is empty"
        if (text.length > MAX_TEXT) return "Bad Request: message is too long"
        for (entity in entities) {
            val fields = entity as? JsonObject ?: return "Bad Request: can't parse entities: an entity is no object"
            val offset = fields.long("offset") ?: -1
            val length = fields.long("length") ?: 0
            if (fields.string("type") == null || offset < 0 || length <= 0 || offset + length > text.length) {
                return "Bad Request: can't parse entities: an entity lies outside the text"
            }
        }
        return null
    }

    private fun breakOff(exchange: HttpExchange) {
        exchange.responseHeaders.add("content-type", "application/json")
        exchange.sendResponseHeaders(200, BROKEN_LENGTH)
        exchange.responseBody.write("""{"ok":true,"res""".toByteArray())
        exchange.responseBody.flush()
    }

    private fun write(exchange: HttpExchange, status: Int, body: String, headers: Map<String, String> = JSON_HEADERS) {
        val bytes = body.toByteArray()
        headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
    }

    private sealed interface Step {
        class Answer(val status: Int, val body: String, val headers: Map<String, String>) : Step

        data object Break : Step

        class Delay(val duration: Duration) : Step

        data object Pass : Step
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val MAX_UPDATES = 100
        const val MAX_TEXT = 4096
        const val MAX_CAPTION = 1024
        const val MAX_DOWNLOAD = 20L * 1024 * 1024
        const val PHOTO_SIDE = 800

        /** The method name under which the fake records and scripts file downloads. */
        const val DOWNLOAD = "download"
        const val TOPIC_COLOR = 7322096
        const val BROKEN_LENGTH = 1000L
        const val CHAT_NOT_FOUND = "Bad Request: chat not found"
        const val THREAD_NOT_FOUND = "Bad Request: message thread not found"
        const val NOT_MODIFIED = "Bad Request: message is not modified: specified new message content and reply " +
            "markup are exactly the same as a current content and reply markup of the message"
        const val BAD_MARKUP = "Bad Request: can't parse reply keyboard markup JSON object"
        const val QUERY_INVALID =
            "Bad Request: query is too old and response timeout expired or query ID is invalid"
        const val MAX_COMMANDS = 100
        const val MAX_DESCRIPTION = 256
        const val BAD_COMMAND = "Bad Request: BOT_COMMAND_INVALID"
        val COMMAND = Regex("[a-z0-9_]{1,32}")
        val LANGUAGE = Regex("[a-z]{2}")
        val SCOPES = setOf(
            "default",
            "all_private_chats",
            "all_group_chats",
            "all_chat_administrators",
            "chat",
            "chat_administrators",
            "chat_member",
        )
        val CHAT_SCOPES = setOf("chat", "chat_administrators", "chat_member")
        val PATH = Regex("/bot([^/]*)/([A-Za-z]+)")
        val FILE_PATH = Regex("/file/bot([^/]*)/(.+)")
        val JSON_HEADERS = mapOf("content-type" to "application/json")

        fun ok(result: JsonElement): JsonObject = buildJsonObject {
            put("ok", true)
            put("result", result)
        }

        fun error(status: Int, description: String, more: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
            put("ok", false)
            put("error_code", status)
            put("description", description)
            more()
        }

        fun params(body: String): JsonObject? = if (body.isBlank()) {
            JsonObject(emptyMap())
        } else {
            try {
                Json.parseToJsonElement(body) as? JsonObject
            } catch (e: SerializationException) {
                null
            }
        }
    }
}

/** A file the fake keeps for the bot to download, of [size] bytes as Telegram reports it. */
private class FakeFile(val bytes: ByteArray, val size: Long, val path: String)

/** The text fields of a `multipart/form-data` body, and its file part. */
private class FakeForm(val params: JsonObject, val upload: Upload?) {
    class Upload(val fileName: String, val mediaType: String, val bytes: ByteArray)

    companion object {
        /** The form in [body] when [contentType] names one, null otherwise. */
        fun parse(contentType: String, body: ByteArray): FakeForm? {
            if (!contentType.startsWith("multipart/form-data")) return null
            val boundary = contentType.substringAfter("boundary=", "").ifEmpty { return null }
            val text = body.toString(Charsets.ISO_8859_1)
            val fields = mutableMapOf<String, JsonElement>()
            var upload: Upload? = null
            for (part in text.split("--$boundary").drop(1).dropLast(1)) {
                val head = part.removePrefix("\r\n").substringBefore("\r\n\r\n")
                val content = part.removePrefix("\r\n").substringAfter("\r\n\r\n").removeSuffix("\r\n")
                val disposition = head.lines().first { it.startsWith("Content-Disposition") }
                val name = Regex("name=\"([^\"]*)\"").find(disposition)?.groupValues?.get(1) ?: continue
                val fileName = Regex("filename=\"([^\"]*)\"").find(disposition)?.groupValues?.get(1)
                val bytes = content.toByteArray(Charsets.ISO_8859_1)
                if (fileName == null) {
                    fields[name] = JsonPrimitive(bytes.toString(Charsets.UTF_8))
                } else {
                    val type = head.lines().firstOrNull { it.startsWith("Content-Type") }?.substringAfter(':')?.trim()
                    val decoded = fileName.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8)
                    upload = Upload(decoded, type.orEmpty(), bytes)
                }
            }
            return FakeForm(JsonObject(fields), upload)
        }
    }
}

private fun JsonObject.long(name: String): Long? = (get(name) as? JsonPrimitive)?.longOrNull

private fun JsonObject.int(name: String): Int? = (get(name) as? JsonPrimitive)?.intOrNull

private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

private fun JsonElement.boolean(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
