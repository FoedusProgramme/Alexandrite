package org.foedusprogramme.alexandrite.store.sqlite

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.plugin.PluginFiles
import org.foedusprogramme.alexandrite.sdk.store.ConversationInfo
import org.foedusprogramme.alexandrite.sdk.transcript.ContextPart
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.transcript.MediaPart
import org.foedusprogramme.alexandrite.sdk.transcript.StoredMedia
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserOrigin
import org.foedusprogramme.alexandrite.testkit.TEST_TIME
import org.foedusprogramme.alexandrite.testkit.testChat
import org.foedusprogramme.alexandrite.testkit.testUser
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

internal val chat: ChatAddress = testChat()
internal val main: AgentChatKey = AgentChatKey(AgentId.MAIN, chat)
internal val member: ChatUser = testUser()
internal val png: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

internal class TestFiles(override val dataDir: Path) : PluginFiles {
    override val cacheDir: Path get() = dataDir.resolve("cache")
}

internal class MutableClock(var instant: Instant = TEST_TIME, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)

    override fun instant(): Instant = instant

    fun advance(duration: Duration) {
        instant += duration
    }
}

internal class TestStore(directory: Path, val clock: MutableClock) {
    val database = StoreDatabase(TestFiles(directory), clock)
    val conversations = SqliteConversationStore(database)
    val media = SqliteMediaStore(database, TestFiles(directory))
    val transcripts = SqliteTranscriptStore(database, media)
    val chatStates = SqliteChatStateStore(database)
    private val turns = AtomicInteger()

    suspend fun start() {
        database.onStart()
        media.onStart()
    }

    suspend fun turn(conversation: ConversationInfo, kind: TurnKind = TurnKind.MESSAGE): TurnInfo {
        val id = TurnId("turn-${turns.incrementAndGet()}")
        val turn = TurnInfo.builder(id, conversation.key.chat, conversation.id, kind)
            .agent(conversation.key.agent)
            .actor(member.takeIf { kind == TurnKind.MESSAGE })
            .build()
        conversations.startTurn(turn)
        return turn
    }

    /** Stores [entries] in a new turn of the current conversation of [key]. */
    suspend fun append(vararg entries: TranscriptEntry, key: AgentChatKey = main): List<TranscriptEntry> =
        transcripts.append(turn(conversations.current(key)).id, entries.toList())

    suspend fun rows(sql: String, vararg arguments: Any?): List<List<Any?>> = database.transaction {
        query(sql, *arguments) { (1..metaData.columnCount).map { getObject(it) } }
    }
}

internal fun <T> withStore(directory: Path, clock: MutableClock = MutableClock(), block: suspend TestStore.() -> T): T =
    runBlocking {
        val store = TestStore(directory, clock)
        try {
            store.start()
            store.block()
        } finally {
            store.database.onDestroy()
        }
    }

internal fun connect(file: Path): Connection = DriverManager.getConnection("jdbc:sqlite:${file.toUri()}")

internal fun Connection.userVersion(): Int = createStatement().use { statement ->
    statement.executeQuery("PRAGMA user_version").use { rows ->
        rows.next()
        rows.getInt(1)
    }
}

internal fun Connection.tables(): Set<String> = createStatement().use { statement ->
    statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use {
        buildSet { while (it.next()) add(it.getString(1)) }
    }
}

internal fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

internal fun message(text: String, id: String = "m-$text", chat: ChatAddress = main.chat): UserEntry = UserEntry(
    null,
    listOf(ContextPart("message-context", "Sender: Member"), TextPart(text)),
    UserOrigin.FromChat(member, ChannelMessageRef(chat, id), TEST_TIME, null, null),
)

internal fun mediaPart(id: StoredMedia, kind: MediaKind = MediaKind.IMAGE): MediaPart =
    MediaPart(kind, "image/png", id, "photo.png", 640, 480)
