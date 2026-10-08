package org.foedusprogramme.alexandrite.testkit.store

import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.chat.TurnLineage
import org.foedusprogramme.alexandrite.sdk.chat.rebuild
import org.foedusprogramme.alexandrite.sdk.store.ConversationKind
import org.foedusprogramme.alexandrite.sdk.store.ConversationState
import org.foedusprogramme.alexandrite.sdk.store.ForkPoint
import org.foedusprogramme.alexandrite.sdk.store.TurnEndKind
import org.foedusprogramme.alexandrite.sdk.store.TurnRecord
import org.foedusprogramme.alexandrite.sdk.transcript.EntryId
import org.foedusprogramme.alexandrite.testkit.StoreCheck
import org.foedusprogramme.alexandrite.testkit.testChat
import org.foedusprogramme.alexandrite.testkit.testUser
import java.time.Instant

internal val CONVERSATION_CHECKS: List<StoreCheck> = listOf(
    StoreCheck("the current conversation is created when first asked for and kept") {
        open {
            val before = Instant.now()
            val first = conversations.current(main)
            val after = Instant.now()

            expectEqual(first, conversations.current(main), "the current conversation asked for again")
            expectEqual(first, conversations.conversation(first.id), "the conversation read by its id")
            expectEqual(ConversationKind.USER_LANE, first.kind, "its kind")
            expectEqual(ConversationState.ACTIVE, first.state, "its state")
            expectEqual(main, first.key, "its key")
            expectWithin(first.createdAt, before, after, "its creation")
            expectEqual(
                listOf(null, null, null, null),
                listOf(first.sealedAt, first.successor, first.lineage, first.fork),
                "its seal, successor, lineage and fork",
            )
        }
    },
    StoreCheck("each agent and each thread of a chat has conversations of its own") {
        open {
            val keys = listOf(
                main,
                coder,
                AgentChatKey(AgentId.MAIN, testChat(thread = "7")),
                AgentChatKey(AgentId.MAIN, testChat("other")),
            )

            val current = keys.map { conversations.current(it) }
            val bases = keys.map { conversations.heartbeatBase(it) }

            expectEqual(8, (current + bases).map { it.id }.toSet().size, "the different conversations")
            expectEqual(current, keys.map { conversations.current(it) }, "the current conversations asked for again")
            expectEqual(bases, keys.map { conversations.heartbeatBase(it) }, "the heartbeat bases asked for again")
            expectEqual(keys + keys, (current + bases).map { it.key }, "their keys")
            expectEqual(List(4) { ConversationKind.HEARTBEAT_BASE }, bases.map { it.kind }, "the kinds of the bases")
        }
    },
    StoreCheck("conversation ids are unique") {
        open {
            val ids = List(20) { conversations.newConversation(main).successor.id }

            expectEqual(20, ids.toSet().size, "the different ids of 20 conversations")
        }
    },
    StoreCheck("conversations outlive the store") {
        val current = open { conversations.current(main) }
        val base = open { conversations.heartbeatBase(main) }

        open {
            expectEqual(current, conversations.current(main), "the current conversation")
            expectEqual(base, conversations.heartbeatBase(main), "the heartbeat base")
        }
    },
    StoreCheck("a new conversation seals the current one and takes its place") {
        open {
            val old = conversations.current(main)
            val before = Instant.now()
            val rotation = conversations.newConversation(main)
            val after = Instant.now()

            val sealed = rotation.sealed ?: fail("A new conversation sealed nothing, but there was a current one.")
            val successor = rotation.successor
            expectWithin(sealed.sealedAt, before, after, "the seal")
            expectEqual(
                old.toBuilder().state(ConversationState.SEALED).sealedAt(sealed.sealedAt).successor(successor.id)
                    .build(),
                sealed,
                "the sealed conversation",
            )
            expectEqual(sealed, conversations.conversation(old.id), "the sealed conversation read by its id")
            expectEqual(successor, conversations.current(main), "the current conversation")
            expectEqual(ConversationState.ACTIVE, successor.state, "the state of the successor")
            expectEqual(ConversationKind.USER_LANE, successor.kind, "the kind of the successor")
            expect(successor.id != old.id) { "The successor has the id ${old.id} of the sealed conversation." }
        }
    },
    StoreCheck("a new conversation where there is none seals nothing") {
        open {
            val rotation = conversations.newConversation(main)

            expectEqual(null, rotation.sealed, "the sealed conversation")
            expectEqual(rotation.successor, conversations.current(main), "the current conversation")
        }
    },
    StoreCheck("a new conversation keeps the heartbeat base and the conversations of other agents") {
        open {
            val base = conversations.heartbeatBase(main)
            val other = conversations.current(coder)

            conversations.newConversation(main)
            conversations.newConversation(main)

            expectEqual(base, conversations.heartbeatBase(main), "the heartbeat base")
            expectEqual(base, conversations.conversation(base.id), "the heartbeat base read by its id")
            expectEqual(other, conversations.current(coder), "the current conversation of another agent")
        }
    },
    StoreCheck("a delegated conversation keeps its lineage and the history it forks") {
        open {
            val parent = conversations.current(main)
            val lineage = lineage(startTurn(parent), "run-1")
            val fork = ForkPoint(parent.id, EntryId(7))

            val child = conversations.createDelegated(reviewer, lineage, fork)

            expectEqual(ConversationKind.DELEGATED, child.kind, "its kind")
            expectEqual(reviewer, child.key, "its key")
            expectEqual(lineage, child.lineage, "its lineage")
            expectEqual(fork, child.fork, "its fork")
            expectEqual(child, conversations.conversation(child.id), "the conversation read by its id")
            expect(conversations.current(reviewer).id != child.id) { "A delegated conversation became current." }
            expectEqual(parent, conversations.current(main), "the parent's conversation")
        }
    },
    StoreCheck("a delegated conversation of a delegated turn descends from the same root") {
        open {
            val root = startTurn(conversations.current(main))
            val childLineage = lineage(root, "run-1")
            val child = conversations.createDelegated(reviewer, childLineage)
            val childTurn = startTurn(child, TurnKind.DELEGATED, childLineage)

            val grandchild = conversations.createDelegated(coder, lineage(childTurn, "run-2"))

            expectEqual(
                TurnLineage(RunId("run-2"), childTurn.id, child.id, ToolCallId("call"), root.id, root.conversation, 2),
                grandchild.lineage,
                "the lineage of the grandchild",
            )
            expectEqual(childLineage, conversations.turn(childTurn.id)?.lineage, "the lineage of the child's turn")
        }
    },
    StoreCheck("a delegated conversation must descend from a recorded parent turn of its chat") {
        open {
            val parentConversation = conversations.current(main)
            val parent = startTurn(parentConversation)
            val good = lineage(parent, "run-1")
            val unrecorded = lineage(parent.rebuild { id(TurnId("missing")) }, "run-1")
            val cases = mapOf(
                "a parent turn that is not recorded" to (reviewer to unrecorded),
                "a parent turn of another conversation" to (reviewer to good.with(ConversationId("other"))),
                "a key of another chat" to (AgentChatKey(reviewer.agent, testChat("x")) to good),
                "a wrong depth" to (reviewer to good.with(depth = 2)),
                "a wrong root" to (reviewer to good.with(rootTurn = TurnId("elsewhere"))),
            )

            for ((case, delegation) in cases) {
                expectThrows<IllegalArgumentException>("A delegated conversation with $case") {
                    conversations.createDelegated(delegation.first, delegation.second)
                }
            }
            expectThrows<IllegalArgumentException>("A delegated conversation forking an unknown one") {
                conversations.createDelegated(reviewer, good, ForkPoint(ConversationId("unknown"), EntryId(1)))
            }
            expectEqual(parentConversation, conversations.current(main), "the parent's conversation")
        }
    },
    StoreCheck("a turn is recorded with its actor and ends once") {
        open {
            val conversation = conversations.current(main)
            val before = Instant.now()
            val turn = startTurn(conversation)
            val started = Instant.now()

            expect(conversations.endTurn(turn.id, TurnEndKind.COMPLETED)) { "The turn did not end." }
            val ended = Instant.now()
            expect(!conversations.endTurn(turn.id, TurnEndKind.FAILED)) { "The turn ended twice." }
            expect(!conversations.endTurn(TurnId("missing"), TurnEndKind.FAILED)) { "An unknown turn ended." }

            val record = conversations.turn(turn.id) ?: fail("The turn is not recorded.")
            expectWithin(record.startedAt, before, started, "its start")
            expectWithin(record.endedAt, started, ended, "its end")
            expectEqual(
                TurnRecord.builder(turn.id, conversation.id, main, TurnKind.MESSAGE, record.startedAt)
                    .actor(testUser().address)
                    .endedAt(record.endedAt)
                    .end(TurnEndKind.COMPLETED)
                    .build(),
                record,
                "the turn",
            )
            expectEqual(null, conversations.turn(TurnId("missing")), "an unknown turn")
        }
    },
    StoreCheck("a turn is recorded in a conversation of its own agent and chat, once") {
        open {
            val conversation = conversations.current(main)
            val turn = startTurn(conversation, TurnKind.HEARTBEAT)
            val record = conversations.turn(turn.id) ?: fail("The turn is not recorded.")

            expectEqual(listOf(null, null, null), listOf(record.actor, record.endedAt, record.end), "its actor and end")
            expectEqual(TurnKind.HEARTBEAT, record.kind, "its kind")
            val cases = mapOf(
                "a turn recorded already" to turn,
                "a turn of another agent" to turn.rebuild {
                    id(TurnId("coder-turn"))
                    agent(coder.agent)
                },
                "a turn of an unknown conversation" to turn.rebuild {
                    id(TurnId("lost"))
                    conversation(ConversationId("missing"))
                },
            )
            for ((case, refused) in cases) {
                expectThrows<IllegalArgumentException>("Starting $case") { conversations.startTurn(refused) }
            }
            expectEqual(null, conversations.turn(TurnId("lost")), "a refused turn")
        }
    },
    StoreCheck("kinds and ends of turns this version does not know are kept as they are") {
        open {
            val turn = startTurn(conversations.current(main), TurnKind.of("vote"))

            conversations.endTurn(turn.id, TurnEndKind.of("paused"))

            val record = conversations.turn(turn.id) ?: fail("The turn is not recorded.")
            expectEqual(TurnKind.of("vote"), record.kind, "its kind")
            expectEqual(TurnEndKind.of("paused"), record.end, "its end")
        }
    },
)

private fun TurnLineage.with(
    parentConversation: ConversationId = this.parentConversation,
    rootTurn: TurnId = this.rootTurn,
    depth: Int = this.depth,
): TurnLineage = TurnLineage(run, parentTurn, parentConversation, parentCall, rootTurn, rootConversation, depth)
