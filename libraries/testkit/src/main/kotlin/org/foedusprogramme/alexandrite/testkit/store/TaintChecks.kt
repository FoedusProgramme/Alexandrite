package org.foedusprogramme.alexandrite.testkit.store

import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.store.ConversationTaint
import org.foedusprogramme.alexandrite.testkit.StoreCheck
import java.time.Instant
import java.time.temporal.ChronoUnit

private val AT: Instant = Instant.parse("2026-01-02T03:04:05.678912Z")

private val KEPT_AT: Instant = AT.truncatedTo(ChronoUnit.MILLIS)

internal val TAINT_CHECKS: List<StoreCheck> = listOf(
    StoreCheck("a conversation's taint is written once, and the first mark is kept") {
        open {
            val conversation = conversations.current(main)
            val other = conversations.current(coder)

            expectEqual(null, conversations.taint(conversation.id), "the taint of a new conversation")
            expect(conversations.markTainted(conversation.id, "a forwarded message", AT)) {
                "The first mark was not written."
            }
            expect(!conversations.markTainted(conversation.id, "a web page", AT.plusSeconds(60))) {
                "A second mark was written."
            }
            expect(!conversations.markTainted(ConversationId("missing"), "a web page", AT)) {
                "An unknown conversation was marked."
            }

            expectEqual(
                ConversationTaint(KEPT_AT, "a forwarded message"),
                conversations.taint(conversation.id),
                "the taint, to the millisecond",
            )
            expectEqual(null, conversations.taint(other.id), "the taint of another agent's conversation")
            expectEqual(null, conversations.taint(ConversationId("missing")), "the taint of an unknown conversation")
            expectEqual(conversation, conversations.conversation(conversation.id), "the marked conversation")
        }
    },
    StoreCheck("a taint outlives the store") {
        val base = open {
            val base = conversations.heartbeatBase(main)
            conversations.markTainted(base.id, "an untrusted prompt", AT)
            base.id
        }

        open {
            expectEqual(ConversationTaint(KEPT_AT, "an untrusted prompt"), conversations.taint(base), "the taint")
            expect(!conversations.markTainted(base, "a web page", AT)) { "A mark was written again." }
        }
    },
    StoreCheck("a new conversation starts untainted, and the ones it keeps or seals keep their marks") {
        open {
            val old = conversations.current(main)
            val base = conversations.heartbeatBase(main)
            conversations.markTainted(old.id, "a forwarded message", AT)
            conversations.markTainted(base.id, "a web page", AT)

            val successor = conversations.newConversation(main).successor
            val next = conversations.newConversation(main).successor

            expectEqual(null, conversations.taint(successor.id), "the taint of the successor")
            expectEqual(null, conversations.taint(next.id), "the taint of the next successor")
            expectEqual(
                ConversationTaint(KEPT_AT, "a forwarded message"),
                conversations.taint(old.id),
                "the taint of the sealed conversation",
            )
            expectEqual(ConversationTaint(KEPT_AT, "a web page"), conversations.taint(base.id), "the heartbeat base")
            expect(conversations.markTainted(successor.id, "a quote", AT)) { "A sealed conversation took no mark." }
            expectEqual(null, conversations.taint(next.id), "the taint of the current conversation")
        }
    },
    StoreCheck("a cleared taint is returned once and gone, and the conversation takes a new mark") {
        val conversation = open {
            val conversation = conversations.current(main)
            val other = conversations.current(coder)
            conversations.markTainted(conversation.id, "a web page", AT)
            conversations.markTainted(other.id, "a forwarded message", AT)

            expectEqual(
                ConversationTaint(KEPT_AT, "a web page"),
                conversations.clearTaint(conversation.id),
                "the cleared mark",
            )
            expectEqual(null, conversations.taint(conversation.id), "the taint once cleared")
            expectEqual(null, conversations.clearTaint(conversation.id), "a second clearing")
            expectEqual(null, conversations.clearTaint(ConversationId("missing")), "the clearing of an unknown one")
            expectEqual(
                ConversationTaint(KEPT_AT, "a forwarded message"),
                conversations.taint(other.id),
                "the taint of another conversation",
            )
            expect(conversations.markTainted(conversation.id, "a quote", AT.plusSeconds(60))) {
                "A cleared conversation took no new mark."
            }
            conversation.id
        }

        open {
            expectEqual(
                ConversationTaint(KEPT_AT.plusSeconds(60), "a quote"),
                conversations.taint(conversation),
                "the new mark after a restart",
            )
        }
    },
)
