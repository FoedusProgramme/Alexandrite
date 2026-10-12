package org.foedusprogramme.alexandrite.channel.onebot.tools

import org.foedusprogramme.alexandrite.channel.onebot.protocol.api.OneBotRegistry
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk

/**
 * The actions of the platform this bot may be asked for, each declared once.
 *
 * One row says what an action is for, which arguments it takes, what they mean and how risky it is. A tool reads the
 * row rather than a class of its own, so an action is added by adding a row, and the ones that must never be called
 * are left out of the table rather than guarded in each tool.
 *
 * An action the standard declares is checked against the registry, so a row naming one that does not exist, or one
 * the standard hides, fails where the table is read rather than at the platform. An action the standard does not
 * declare at all is a row that says so, which is how an implementation's own actions are reached.
 */
internal object OneBotActions {
    /**
     * What an action is, and what calling it takes.
     *
     * [parameters] names the arguments the platform takes, in the order they are shown. An argument that must be given
     * says so, and the one an action about a message takes may be left out, in which case the message of this turn is
     * meant: a prompt does not name the id of the message it answers, so a call about it must not have to.
     */
    internal class Row(
        val name: String,
        val said: String,
        val parameters: List<Parameter>,
        /** Whether the standard declares this action at all. An implementation may have actions it does not. */
        val extension: Boolean = false,
    ) {
        /** Whether this action changes something rather than only reading. */
        val changes: Boolean get() = this in changing

        /** Whether the id of the message of this turn stands in for an argument that was left out. */
        val takesAMessage: Boolean get() = parameters.any { it.name == MESSAGE_ID && it.optional }
    }

    /** One argument of an action. */
    internal class Parameter(val name: String, val description: String, val required: Boolean = true) {
        /** Whether this argument may be left out. */
        val optional: Boolean get() = !required
    }

    /** The argument that names the message an action is about. */
    const val MESSAGE_ID: String = "message_id"

    private val changing: List<Row> = listOf(
        Row(
            name = "delete_msg",
            said = "The message was taken back.",
            parameters = listOf(
                Parameter(
                    MESSAGE_ID,
                    "The message to take back. Default: the message this turn is about.",
                    required = false,
                ),
            ),
        ),
        Row(
            name = "set_msg_emoji_like",
            said = "The emoji response was added.",
            parameters = listOf(
                Parameter(
                    MESSAGE_ID,
                    "The message to respond to. Default: the message this turn is about.",
                    required = false,
                ),
                Parameter("emoji_id", "The number of the emoji, such as 128077 for a thumbs up."),
            ),
            extension = true,
        ),
        Row(
            name = "set_friend_add_request",
            said = "The friend request was answered.",
            parameters = listOf(
                Parameter("flag", "The flag the request reported."),
                Parameter("approve", "Whether to accept the request: true or false."),
                Parameter("remark", "The remark to keep with a friend the call accepts.", required = false),
            ),
        ),
        Row(
            name = "set_group_add_request",
            said = "The request was answered.",
            parameters = listOf(
                Parameter("flag", "The flag the request reported."),
                Parameter("sub_type", "add when somebody asks to join, invite when the bot is invited."),
                Parameter("approve", "Whether to accept the request: true or false."),
                Parameter("reason", "The reason to give for a request the call refuses.", required = false),
            ),
        ),
        Row(
            name = "set_group_ban",
            said = "The mute was set.",
            parameters = listOf(
                Parameter("group_id", "The group."),
                Parameter("user_id", "The member to mute."),
                Parameter("duration", "How many seconds the mute lasts, 0 to lift it."),
            ),
        ),
        Row(
            name = "set_group_kick",
            said = "The member was removed from the group.",
            parameters = listOf(
                Parameter("group_id", "The group."),
                Parameter("user_id", "The member to remove."),
                Parameter("reject_add_request", "Whether to refuse their requests to join again.", required = false),
            ),
        ),
        Row(
            name = "set_group_special_title",
            said = "The title was set.",
            parameters = listOf(
                Parameter("group_id", "The group."),
                Parameter("user_id", "The member."),
                Parameter("special_title", "The title to give them."),
            ),
        ),
    )

    private val reading: List<Row> = listOf(
        Row(
            name = "get_msg",
            said = "The message was read.",
            parameters = listOf(
                Parameter(
                    MESSAGE_ID,
                    "The message to read. Default: the message this turn is about.",
                    required = false,
                ),
            ),
        ),
        Row(
            name = "get_group_member_info",
            said = "The member was read.",
            parameters = listOf(
                Parameter("group_id", "The group."),
                Parameter("user_id", "The member to read."),
                Parameter(
                    "no_cache",
                    "Whether to read the member afresh rather than from the cache.",
                    required = false,
                ),
            ),
        ),
    )

    /** Everything this bot may be asked for, the reads first. */
    val rows: List<Row> = reading + changing

    private val byName: Map<String, Row> = rows.associateBy(Row::name)

    init {
        rows.forEach { row ->
            if (!row.extension) {
                val declared = OneBotRegistry.action(row.name)
                require(declared != null) { "The platform declares no action '${row.name}'." }
                require(!declared.hidden) { "The action '${row.name}' is one the standard hides." }
            }
        }
        require(byName.size == rows.size) { "An action is declared twice." }
    }

    /** The row of [name], null when this bot may not be asked for it. */
    fun of(name: String): Row? = byName[name]
}
