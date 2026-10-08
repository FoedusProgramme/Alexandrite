package org.foedusprogramme.alexandrite.testkit.store

import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.testkit.StoreCheck
import org.foedusprogramme.alexandrite.testkit.testChat

internal val CHAT_STATE_CHECKS: List<StoreCheck> = listOf(
    StoreCheck("a chat's own value and each agent's value there are apart") {
        open {
            chatStates.write("notes", "mode", null, chat, "\"plain\"")
            chatStates.write("notes", "mode", AgentId.MAIN, chat, "\"rich\"")

            expectEqual("\"plain\"", chatStates.read("notes", "mode", null, chat), "the chat's own value")
            expectEqual("\"rich\"", chatStates.read("notes", "mode", AgentId.MAIN, chat), "the main agent's value")
            expectEqual(null, chatStates.read("notes", "mode", coder.agent, chat), "another agent's value")
        }
    },
    StoreCheck("a thread is a key of its own") {
        val thread = testChat(thread = "7")
        open {
            chatStates.write("notes", "mode", null, chat, "1")

            expectEqual(null, chatStates.read("notes", "mode", null, thread), "the thread's value")
            chatStates.write("notes", "mode", null, thread, "2")
            expectEqual("1", chatStates.read("notes", "mode", null, chat), "the chat's value")
            expectEqual("2", chatStates.read("notes", "mode", null, thread), "the thread's own value")
        }
    },
    StoreCheck("plugins and names are apart") {
        open {
            chatStates.write("notes", "mode", null, chat, "1")
            chatStates.write("notes", "size", null, chat, "2")
            chatStates.write("other", "mode", null, chat, "3")

            expectEqual(
                listOf("1", "2", "3"),
                listOf("notes" to "mode", "notes" to "size", "other" to "mode").map { (plugin, name) ->
                    chatStates.read(plugin, name, null, chat)
                },
                "the values",
            )
        }
    },
    StoreCheck("a value is replaced by the next and removed by null") {
        open {
            chatStates.write("notes", "mode", coder.agent, chat, "1")
            chatStates.write("notes", "mode", coder.agent, chat, "2")

            expectEqual("2", chatStates.read("notes", "mode", coder.agent, chat), "the replaced value")

            chatStates.write("notes", "mode", coder.agent, chat, null)

            expectEqual(null, chatStates.read("notes", "mode", coder.agent, chat), "the removed value")
            chatStates.write("notes", "mode", coder.agent, testChat("never"), null)
        }
    },
    StoreCheck("values outlive the store") {
        val thread = testChat(thread = "7")
        open { chatStates.write("notes", "mode", coder.agent, thread, "\"kept\"") }

        open { expectEqual("\"kept\"", chatStates.read("notes", "mode", coder.agent, thread), "the value") }
    },
)
