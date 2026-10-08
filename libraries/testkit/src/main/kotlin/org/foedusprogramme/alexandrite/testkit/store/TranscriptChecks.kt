package org.foedusprogramme.alexandrite.testkit.store

import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.store.storeInline
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.EntryId
import org.foedusprogramme.alexandrite.sdk.transcript.EntryRecord
import org.foedusprogramme.alexandrite.sdk.transcript.InlineMedia
import org.foedusprogramme.alexandrite.sdk.transcript.MediaId
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.transcript.MediaPart
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningPart
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningSeal
import org.foedusprogramme.alexandrite.sdk.transcript.SealKind
import org.foedusprogramme.alexandrite.sdk.transcript.StoredMedia
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.testkit.StoreCheck
import org.foedusprogramme.alexandrite.testkit.testChat
import java.time.Instant

internal val TRANSCRIPT_CHECKS: List<StoreCheck> = listOf(
    StoreCheck("every entry is read back as it was stored, with its record") {
        open {
            val samples = samples(media.put(png, MediaKind.IMAGE, "image/png"))
            val conversation = conversations.current(main)
            val turn = startTurn(conversation)
            val before = Instant.now()
            val stored = transcripts.append(turn.id, samples)
            val after = Instant.now()

            val records = stored.map { it.record ?: fail("An appended entry came back without a record.") }
            records.forEach { expectWithin(it.createdAt, before, after, "the time entry ${it.id} was stored") }
            expectIncreasing(records.map { it.id })
            expectEqual(
                samples.zip(records) { entry, record ->
                    entry.withRecord(EntryRecord(record.id, conversation.id, turn.id, record.createdAt))
                },
                stored,
                "the appended entries",
            )
            expectEqual(stored, transcripts.entries(conversation.id), "the entries of the conversation")
            expectEqual(stored, records.map { transcripts.entry(it.id) }, "the entries read by their ids")
        }
    },
    StoreCheck("entries outlive the store") {
        val stored = open { append(message("kept")) }

        open {
            val conversation = stored.single().record?.conversation ?: fail("The entry came back without a record.")
            expectEqual(stored, transcripts.entries(conversation), "the entries of the conversation")
        }
    },
    StoreCheck("a reasoning seal is kept byte for byte") {
        val data = "\"\\\n\t\u0000\u001f é😺 ${"Zm9v".repeat(500)}=="
        val reply = AssistantEntry(
            null,
            listOf(ReasoningPart("r", null, ReasoningSeal(model, anthropic, SealKind.REDACTED, data))),
            model,
        )
        val id = open { append(reply).single().record?.id ?: fail("The entry came back without a record.") }

        open {
            val read = transcripts.entry(id) as? AssistantEntry ?: fail("Entry $id is no longer the reply.")
            val kept = (read.parts.single() as? ReasoningPart)?.seal?.data
            expectEqual(data.encodeToByteArray().toList(), kept?.encodeToByteArray()?.toList(), "the seal's bytes")
        }
    },
    StoreCheck("ids grow with every entry and are never used again") {
        open {
            val first = append(message("a"), message("b"))
            transcripts.deleteMessage(ChannelMessageRef(chat, "m-b"))
            val second = append(message("c"))

            expectIncreasing((first + second).map { it.record?.id ?: fail("An entry came back without a record.") })
        }
    },
    StoreCheck("a tail holds the last entries in order") {
        open {
            val stored = append(message("a"), message("b"), message("c"))
            val conversation = stored.first().record?.conversation ?: fail("An entry came back without a record.")

            expectEqual(stored.drop(1), transcripts.tail(conversation, 2), "the tail of two entries")
            expectEqual(stored, transcripts.tail(conversation, 5), "the tail of five entries")
            expectEqual(emptyList(), transcripts.tail(conversation, 0), "the tail of no entry")
            expectEqual(emptyList(), transcripts.entries(ConversationId("missing")), "the entries of no conversation")
            expectEqual(null, transcripts.entry(EntryId(Long.MAX_VALUE)), "an unknown entry")
        }
    },
    StoreCheck("the entries of a message are found by its reference") {
        open {
            val heartbeat = samples(media.put(png, MediaKind.IMAGE, "image/png"))[1]
            val stored = append(message("a"), message("b"), heartbeat)

            expectEqual(listOf(stored[1]), transcripts.entries(ChannelMessageRef(chat, "m-b")), "the entries of m-b")
            expectEqual(emptyList(), transcripts.entries(ChannelMessageRef(chat, "m-x")), "the entries of m-x")
            expectEqual(
                emptyList(),
                transcripts.entries(ChannelMessageRef(testChat("unknown"), "m-a")),
                "the entries of m-a in another chat",
            )
        }
    },
    StoreCheck("an entry with a record is refused") {
        open {
            val stored = append(message("a")).single()

            expectThrows<IllegalArgumentException>("Appending an entry with a record") { append(message("b"), stored) }
            expectEqual(listOf(stored), transcripts.entries(stored.record!!.conversation), "the entries stored")
        }
    },
    StoreCheck("an entry holding inline media is refused") {
        open {
            val conversation = conversations.current(main)
            val turn = startTurn(conversation)
            val inline = MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(png))
            val entries = listOf(
                UserEntry(null, listOf(TextPart("look"), inline), message("x").origin),
                ToolResultEntry(null, ToolCallId("c"), "files.read", listOf(inline), ToolOutcome.Succeeded),
            )

            for (entry in entries) {
                expectThrows<IllegalArgumentException>("Appending an entry with inline media") {
                    transcripts.append(turn.id, listOf(message("ok"), entry))
                }
            }
            expectEqual(emptyList(), transcripts.entries(conversation.id), "the entries stored")
        }
    },
    StoreCheck("inline media are stored by the helper") {
        open {
            val inline = MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(png), "page.png")
            val entry = ToolResultEntry(
                null,
                ToolCallId("c"),
                "browser.snapshot",
                listOf(TextPart("The page:"), inline),
                ToolOutcome.Succeeded,
            )

            val appended = append(media.storeInline(entry)).single() as ToolResultEntry

            val part = appended.content[1] as MediaPart
            val source = part.source as? StoredMedia ?: fail("The stored part holds ${part.source}.")
            expectEqual(entry.content[0], appended.content[0], "the text part")
            expectEqual("page.png", part.name, "the name of the media")
            expectEqual(png.toList(), media.read(source.id)?.toList(), "the stored bytes")
            expectEqual(message("x"), media.storeInline(message("x")), "an entry without inline media")
        }
    },
    StoreCheck("an entry referring to unknown media is refused") {
        open {
            val conversation = conversations.current(main)
            val turn = startTurn(conversation)
            val entry = UserEntry(null, listOf(mediaPart(StoredMedia(MediaId("missing")))), message("x").origin)

            expectThrows<IllegalArgumentException>("Appending an entry with unknown media") {
                transcripts.append(turn.id, listOf(message("ok"), entry))
            }
            expectEqual(emptyList(), transcripts.entries(conversation.id), "the entries stored")
        }
    },
    StoreCheck("a sealed conversation takes no entries") {
        open {
            val old = conversations.current(main)
            val turn = startTurn(old)
            conversations.newConversation(main)

            expectThrows<IllegalStateException>("Appending to a sealed conversation") {
                transcripts.append(turn.id, listOf(message("late")))
            }
            expectEqual(emptyList(), transcripts.entries(old.id), "the entries of the sealed conversation")
        }
    },
    StoreCheck("entries of a turn that is not recorded are refused") {
        open {
            expectThrows<IllegalArgumentException>("Appending in an unknown turn") {
                transcripts.append(TurnId("missing"), listOf(message("x")))
            }
        }
    },
    StoreCheck("deleting a message removes its entries and the media nothing else refers to") {
        open {
            val own = media.put(png, MediaKind.IMAGE, "image/png")
            val shared = media.put(byteArrayOf(1, 2, 3), MediaKind.FILE, "application/octet-stream")
            val deleted = UserEntry(null, listOf(mediaPart(own), mediaPart(shared)), message("d").origin)
            val kept = ToolResultEntry(null, ToolCallId("c"), "read", listOf(mediaPart(shared)), ToolOutcome.Succeeded)
            val stored = append(message("before"), deleted, kept)

            expectEqual(1, transcripts.deleteMessage(ChannelMessageRef(chat, "m-d")), "the entries deleted")

            expectEqual(
                listOf(stored[0], stored[2]),
                transcripts.entries(stored[0].record!!.conversation),
                "the entries left",
            )
            expectEqual(null, media.info(own.id), "the media only the deleted entry referred to")
            expectEqual(null, media.read(own.id), "the bytes only the deleted entry referred to")
            expectEqual(listOf<Byte>(1, 2, 3), media.read(shared.id)?.toList(), "the media another entry refers to")
            expectEqual(0, transcripts.deleteMessage(ChannelMessageRef(chat, "m-d")), "the entries deleted again")
        }
    },
)

/** Stores [entries] in a new turn of the current conversation of [main]. */
private suspend fun Ports.append(vararg entries: TranscriptEntry): List<TranscriptEntry> =
    transcripts.append(startTurn(conversations.current(main)).id, entries.toList())

private fun expectIncreasing(ids: List<EntryId>) {
    expect(ids.zipWithNext().all { (first, next) -> first.value < next.value }) {
        "Entry ids grow with every entry and are never used again, but were $ids."
    }
}
