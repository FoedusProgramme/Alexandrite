# Telegram

How the Telegram channel works: a bot's config keys, admins and trusted people, pairing chats, groups and privacy mode, commands, approvals by button, and the Telegram tools.

## A bot

Each bot is a channel instance at `channels.telegram.instances.<name>` (`main` by default), served by the agents whose
`agent.agents.<id>.channels` name `telegram:<name>`. Its chats are addressed `telegram:<name>:<chat id>`, a group's id
being negative, with `#<thread>` for a forum topic. An operator adds a bot with `alexandrite channel add telegram`
(see alexandrite-app/cli). The keys of `channels.telegram.instances.<name>`:

- `botToken`: the token from @BotFather, written as `${env:NAME}`.
- `adminUserIds`: the Telegram user ids of the bot's admins, who are the operators of its chats.
- `trustedUserIds`: user ids whose own messages and files do not taint; trust gives them no authority.
- `allowedChats`: chat ids paired by the config, beside those paired by code.
- `groupMessages`: `addressed` (the default: commands for the bot, mentions of it and replies to it) or `all`.
- `approvalMinutes` (10): how long an approval waits for an admin.
- `replayWindowMinutes` (1440): how old a message that a stop cut off may be and still be answered after a restart.
- `streaming`: `enabled`, `mode` (`edit`, or `natural` for paragraphs as separate messages), `quote` (whether a reply
  quotes the message it answers), `editIntervalMillis`, `groupEditIntervalMillis`.
- `commandMenu` (true): whether the bot publishes its command menu.
- `commands`: who may run a command, `{"<command>": "anyone" | "direct" | "unlinked_direct" | "admin"}`.
- `polling` (`timeoutSeconds`, `limit`, `retryMaxSeconds`) and `apiBaseUrl`.

Changes need a restart. Without `adminUserIds` nobody may run admin commands and agents offer tools to nobody.

## Pairing

The bot answers only paired chats. An admin's direct chat and `allowedChats` are paired by the config. Any other chat
that writes to the bot gets a pairing code, valid for 10 minutes, which it gives the operator; the operator pairs it on
the host with `alexandrite pair <code>` (or drops it with `alexandrite deny <code>`). `alexandrite pairings` lists the
waiting and paired chats and `alexandrite unpair <chat>` unpairs one.

## Groups and privacy mode

In a group the bot answers what `groupMessages` lets through. With Telegram's privacy mode on (the default for bots),
a bot that is no group admin gets only commands for it and replies to it, not mentions or other messages: make the bot
an admin of the group, or turn privacy off with /setprivacy at @BotFather and add the bot to the group again.

## Commands

The bot shows the commands of every plugin in its menu (see alexandrite-commands/commands for the list). Admins may run
every command everywhere. For others each command has a default rule, bare and with arguments: `anyone`, `direct`
(anyone in a direct chat, admins elsewhere), `unlinked_direct` (anyone in a direct chat linked with no other chat) or
`admin`; `commands` in the config overrides it, but forms that a command keeps for operators, such as `/trust`,
`/untrust`, `/untaint` and `/allow`, stay with admins. `/streaming` (`edit` or `natural`, `quote` or `noquote`,
`default`) sets how replies come in a chat.

## Approvals

An approval request of an admin's turn is posted in its chat; one of anyone else's turn or of an automated turn goes to
the admins' direct chats. Only admins can press its buttons (see alexandrite-agent/permissions); it expires after
`approvalMinutes`. Offers to trust a source after an automated turn took it in come the same way, with Keep and Trust
buttons, and so do offers to trust a folder whose project instructions were not loaded, with Trust (for the agent) and
Not now.

## Telegram tools

- telegram.react [agent_state]: react with an emoji to a message, by default the one you answer.
- telegram.pin [external_action]: pin a message.
- telegram.set_title [external_action]: rename the group; the bot must be an admin allowed to.
- telegram.poll [external_action]: send a poll.
- telegram.sticker [agent_state]: send a sticker by the id a received sticker shows.
- telegram.member [network_read]: look up a member of the chat and their role.

Messages with files, photos, voice notes and albums are taken in, their files saved to the conversation's `inbox/`.
