package org.unitedsoftware.alexandrite.testkit.store

import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.store.TranscriptMatch
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeKind
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.testkit.StoreCheck
import org.unitedsoftware.alexandrite.testkit.testChat

internal val SEARCH_CHECKS: List<StoreCheck> = listOf(
    StoreCheck("a search finds the entries whose text holds every word, ignoring case") {
        open {
            val stored = appendTo(
                main,
                message("Deploy the Rocket today"),
                AssistantEntry(null, listOf(TextPart("The rocket launched.")), model),
                ToolResultEntry(
                    null,
                    ToolCallId("c"),
                    "fs.read",
                    listOf(TextPart("ROCKET fuel")),
                    ToolOutcome.Succeeded,
                ),
                UserEntry(null, listOf(TextPart("rocket rules")), UserOrigin.SystemWritten("alexandrite.project")),
                UserEntry(null, listOf(ContextPart("message-context", "rocket")), message("x").origin),
                NoticeEntry(null, "No rocket.", NoticeKind.FAILED),
                message("Nothing here"),
            )

            val found = transcripts.search("rocket", setOf(main), 10)

            expectEqual(stored.take(3).ids(), found.map { it.entry }.ids(), "the entries found")
            expectEqual(stored.take(3).toSet(), found.map { it.entry }.toSet(), "the entries found, as stored")
            for (match in found) {
                expectEqual(main, match.key, "the key of a match")
                expect(match.snippet.contains("rocket", ignoreCase = true)) {
                    "The snippet of a match holds the word, but was \"${match.snippet}\"."
                }
            }
            expectEqual(stored.take(1), transcripts.search("deploy ROCKET", setOf(main), 10).entries(), "two words")
            expectEqual(stored.subList(1, 2), transcripts.search("launch", setOf(main), 10).entries(), "a word's head")
            expectEqual(emptyList(), transcripts.search("rocket moon", setOf(main), 10), "a word no entry holds")
            expectEqual(emptyList(), transcripts.search(" \n ", setOf(main), 10), "a blank query")
            expectEqual(emptyList(), transcripts.search("rocket", emptySet(), 10), "no keys")
            expectThrows<IllegalArgumentException>("A search for no entry") {
                transcripts.search("rocket", setOf(main), 0)
            }
        }
    },
    StoreCheck("a search looks in every conversation of the keys it is given, sealed ones included") {
        open {
            val other = AgentChatKey(AgentId.MAIN, testChat("other"))
            val sealed = appendTo(main, message("alpha one"))
            conversations.newConversation(main)
            val current = appendTo(main, message("alpha two"))
            appendTo(coder, message("alpha three"))
            val elsewhere = appendTo(other, message("alpha four", chat = other.chat))

            expectEqual(
                (sealed + current).ids(),
                transcripts.search("alpha", setOf(main), 10).entries().ids(),
                "the entries of main",
            )
            val both = transcripts.search("alpha", setOf(main, other), 10)
            expectEqual((sealed + current + elsewhere).ids(), both.entries().ids(), "the entries of main and other")
            expectEqual(other, both.single { it.entry == elsewhere.single() }.key, "the key of the other chat's entry")
        }
    },
    StoreCheck("a search finds at most its limit") {
        open {
            val stored = appendTo(main, *(1..5).map { message("beta $it") }.toTypedArray())

            val found = transcripts.search("beta", setOf(main), 2).entries()

            expectEqual(2, found.size, "the number of entries found")
            expect(stored.containsAll(found)) { "The entries found are entries stored, but were $found." }
        }
    },
    StoreCheck("a deleted message is no longer found") {
        open {
            appendTo(main, message("gamma", id = "m-d"), message("other"))
            transcripts.deleteMessage(ChannelMessageRef(chat, "m-d"))

            expectEqual(emptyList(), transcripts.search("gamma", setOf(main), 10), "the entries found")
        }
    },
    StoreCheck("entries are found after the store reopens") {
        val stored = open { appendTo(main, message("delta kept")) }

        open { expectEqual(stored, transcripts.search("delta", setOf(main), 10).entries(), "the entries found") }
    },
)

/** Stores [entries] in a new turn of the current conversation of [key]. */
private suspend fun Ports.appendTo(key: AgentChatKey, vararg entries: TranscriptEntry): List<TranscriptEntry> =
    transcripts.append(startTurn(conversations.current(key)).id, entries.toList())

private fun List<TranscriptMatch>.entries(): List<TranscriptEntry> = map { it.entry }

private fun List<TranscriptEntry>.ids(): Set<Long> =
    mapTo(HashSet()) { it.record?.id?.value ?: fail("An entry came back without a record.") }
