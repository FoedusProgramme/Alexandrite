# Usage and budgets

How Alexandrite counts the tokens and money that agents spend, how `/usage` shows them, and what daily and monthly budgets and per-turn limits do.

## Usage

Every model call is counted: the agent's own turns, the judge, conversation summaries, web.fetch answers and
sub-agents' runs. Usage is kept per day, agent, chat and model for 400 days. `/usage` shows the agent's usage in this
chat and in all its chats (today, the last 7 days, this month) and its budgets; `/usage all`, for operators, adds every
agent. `/context` shows how much of the model's context window the conversation takes.

Money is counted where a model has a price: `price` of a model in its endpoint's config
(`providers.<provider>.endpoints.<endpoint>.models.<model>.price`, US dollars per million tokens of `input`, `output`
and `cached` input), or the price its provider lists (OpenRouter does). A model without a price counts tokens only.

## Budgets

`agent.agents.<id>.budget.daily` and `agent.agents.<id>.budget.monthly` each take `tokens` (input and output together)
and/or `money` (US dollars), counted in the agent's zone. A money budget needs a price for every model the agent uses.

At 80 % and at 100 % of a period's budget the agent's home chat, else the chat at hand, gets a notice once. Once a
budget is spent, until the period ends, the agent answers only operators' messages: scheduled tasks, heartbeats,
background work, other agents' messages and the messages of other people are refused, and a job's result is held for the next turn that runs.

## Limits of one turn

- `agent.agents.<id>.maxRounds`: the tool rounds of one turn.
- `agent.agents.<id>.maxTurnTokens`: the input and output tokens of one turn's model calls; past it the answer ends and
  may be incomplete.
- `agent.agents.<id>.maxOutputTokens`: the output of one model call.

Compaction (`agent.agents.<id>.compaction`) keeps long conversations within the context window; `/compact` compacts at
once, `/compact <focus>` names what the summary should keep in view.
