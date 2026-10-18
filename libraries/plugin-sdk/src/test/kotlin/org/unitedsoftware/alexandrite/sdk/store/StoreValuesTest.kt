package org.unitedsoftware.alexandrite.sdk.store

import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.chat.TurnLineage
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.transcript.EntryId
import org.unitedsoftware.alexandrite.sdk.transcript.InlineMedia
import org.unitedsoftware.alexandrite.sdk.transcript.MediaId
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeKind
import org.unitedsoftware.alexandrite.sdk.transcript.StoredMedia
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StoreValuesTest {
    private val instance = ChannelInstanceId(ChannelType("telegram"), "work")
    private val key = AgentChatKey(AgentId.MAIN, ChatAddress(instance, "-100"))
    private val time = Instant.parse("2026-01-01T00:00:00Z")
    private val lineage = TurnLineage(
        RunId("r1"),
        TurnId("t0"),
        ConversationId("c0"),
        ToolCallId("call"),
        TurnId("t0"),
        ConversationId("c0"),
        depth = 1,
    )

    private fun conversation(): ConversationInfo.Builder =
        ConversationInfo.builder(ConversationId("c1"), ConversationKind.USER_LANE, key, ConversationState.ACTIVE, time)

    @Test
    fun `a rebuilt conversation keeps what the block leaves alone`() {
        val delegated = conversation().kind(ConversationKind.DELEGATED).lineage(lineage)
            .fork(ForkPoint(ConversationId("c0"), EntryId(7)))
            .build()

        val sealed = delegated.rebuild {
            state(ConversationState.SEALED)
            sealedAt(time.plusSeconds(1))
            successor(ConversationId("c2"))
        }

        assertEquals(delegated, delegated.toBuilder().build())
        assertEquals(
            conversation().kind(ConversationKind.DELEGATED).lineage(lineage)
                .fork(ForkPoint(ConversationId("c0"), EntryId(7))).state(ConversationState.SEALED)
                .sealedAt(time.plusSeconds(1)).successor(ConversationId("c2")).build(),
            sealed,
        )
    }

    @Test
    fun `a rebuilt turn record keeps what the block leaves alone`() {
        val record = TurnRecord.builder(TurnId("t1"), ConversationId("c1"), key, TurnKind.DELEGATED, time)
            .actor(UserAddress(instance, "1"))
            .lineage(lineage)
            .build()

        val ended = record.rebuild {
            endedAt(time.plusSeconds(3))
            end(TurnEndKind.COMPLETED)
        }

        assertEquals(record, record.toBuilder().build())
        assertNull(record.end)
        assertEquals(TurnEndKind.COMPLETED, ended.end)
        assertEquals(time.plusSeconds(3), ended.endedAt)
        assertEquals(record.lineage, ended.lineage)
    }

    @Test
    fun `a turn record's chat is its key's chat unless the turn came from another chat`() {
        val member = ChatAddress(instance, "member")
        val record = TurnRecord.builder(TurnId("t1"), ConversationId("c1"), key, TurnKind.MESSAGE, time).build()
        val linked = record.rebuild { chat(member) }
        val other = AgentChatKey(key.agent, ChatAddress(instance, "other"))

        assertEquals(key.chat, record.chat)
        assertEquals(member, linked.chat)
        assertEquals(linked, linked.toBuilder().build())
        assertEquals(other.chat, record.rebuild { key(other) }.chat)
        assertEquals(member, linked.rebuild { key(other) }.chat)
    }

    @Test
    fun `store inline puts the inline media of user entries and tool results into the store`() = runTest {
        val puts = mutableListOf<String>()
        val media = object : MediaStore {
            override suspend fun put(bytes: ByteArray, kind: MediaKind, mediaType: String): StoredMedia {
                puts += "$kind $mediaType ${bytes.size}"
                return StoredMedia(MediaId("m${puts.size}"))
            }

            override suspend fun read(id: MediaId): ByteArray? = null

            override suspend fun info(id: MediaId): MediaInfo? = null
        }
        val inline = MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(byteArrayOf(1, 2)), "a.png", 2, 1)
        val stored = MediaPart(MediaKind.FILE, "text/plain", StoredMedia(MediaId("kept")))
        val origin = UserOrigin.FromChat(
            ChatUser(UserAddress(instance, "1"), "Ada", null, isBot = false, isOperator = false),
            ChannelMessageRef(key.chat, "9"),
            time,
            null,
            null,
        )
        val notice = NoticeEntry(null, "Done.", NoticeKind.FAILED)

        val user = media.storeInline(UserEntry(null, listOf(TextPart("look"), inline, stored), origin))
        val result = media.storeInline(
            ToolResultEntry(null, ToolCallId("c"), "browser.snapshot", listOf(inline), ToolOutcome.Succeeded),
        )

        val image = MediaPart(MediaKind.IMAGE, "image/png", StoredMedia(MediaId("m1")), "a.png", 2, 1)
        assertEquals(UserEntry(null, listOf(TextPart("look"), image, stored), origin), user)
        assertEquals(
            ToolResultEntry(
                null,
                ToolCallId("c"),
                "browser.snapshot",
                listOf(MediaPart(MediaKind.IMAGE, "image/png", StoredMedia(MediaId("m2")), "a.png", 2, 1)),
                ToolOutcome.Succeeded,
            ),
            result,
        )
        assertEquals(notice, media.storeInline(notice))
        assertEquals(listOf("image image/png 2", "image image/png 2"), puts)
    }
}
