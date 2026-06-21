# claude-sessions

Introspect **Claude Code session logs** — and their subagents — from the command
line. Built for **evals**: see exactly how tools, skills, and subagents were used
in a past session, so you can judge whether a tool's or skill's *description* led
to correct behavior and tune it.

It's a **thin extractor**, not an analyzer. It normalizes the raw JSONL Claude
Code writes under `~/.claude/projects/`, pairs every tool call with its result,
separates genuine errors from user declines, and lets you **slice** (by tool,
kind, index range, regex, time) so you pull only the part you need. Output is
**JSON / JSONL designed for an LLM to read** — there is no human TUI. The bundled
[`SKILL.md`](./SKILL.md) is a Claude Code skill that teaches an agent how to drive it.

## Why

When you're iterating on tool definitions, MCP servers, or skills, the question
is *"how were they actually used?"* The answer lives in Claude Code's session
logs, but they're large, deeply nested JSONL. This gives an agent (or you) cheap,
sliceable views:

- which tools a session called, how often, and how many *really* failed (vs. were declined by the user)
- the full input **and** result of every call to a given tool — to judge whether its description led to correct usage
- whether a skill fired when it should have, and what prompt preceded it
- what each subagent did: its prompt, tools, turns, errors, and token cost
- end-to-end token/cost accounting across the whole session tree

## Requirements

[Babashka](https://babashka.org) (`bb`). No other dependencies — `cheshire`,
`babashka.fs`, and the arg parser ship with bb.

## Install

Run it directly from a clone:

```bash
git clone https://github.com/bhauman/introspection-skill
introspection-skill/bin/claude-sessions help
```

…or install it as a user-scope **Claude Code skill** so an agent can discover and
drive it by name:

```bash
ln -s "$PWD/introspection-skill" ~/.claude/skills/claude-sessions
```

The CLI works from any directory — the `bin/` wrapper locates its own `bb.edn`.

## Quick start

```bash
# 1. find a session (terse, newest first; last 3 weeks by default)
claude-sessions list --all-projects
# {"id":"a1b2c3d4-…","mtime":"2026-…","messages":228,"subagents":4,"cwd":"…","title":"…"}

# 2. cheap structural map of one session (address by id prefix)
claude-sessions summary a1b2c3d4
# { "tool_calls": 35, "tool_errors": 0, "tool_rejected": 1,
#   "tools": [ {"name":"Bash","calls":12}, … ],
#   "subagents": { "count":4, "with_errors":1, "tool_errors":5 },
#   "tokens": { "output":161870, "cache_read_pct":92 } }

# 3. judge how a tool was used — input+result paired, full content
claude-sessions tool a1b2c3d4 'Bash' --errors-only

# 4. dive into a subagent as if it were its own session
claude-sessions subagents a1b2c3d4
claude-sessions transcript a1b2c3d4/a5b94292 --kind tool_use
```

## Commands

| Command | What it gives you |
|---|---|
| `list` | find sessions (terse, newest first; TIME + SCOPE filters) |
| `summary <h>` | one-call map: counts, errors vs. rejects, tools, skills, subagent rollup, tokens |
| `tools <h>` | per-tool rollup — calls, errors, rejected, mcp |
| `tool <h> <glob>` | every call to matching tool(s); tool_use paired with tool_result, full content |
| `skills <h>` | each `Skill` invocation + the prompt that preceded it |
| `transcript <h>` | normalized JSONL event stream |
| `subagents <h>` | per-subagent turns/tools/errors/tokens; dive in via `<h>/<agent>` |
| `tokens <h>` | token accounting (`--by total\|message\|model`, `--include-subagents`) |
| `event <h> <i>` | one full event by index (expand a truncated one) |

Run `claude-sessions help` for every flag.

## Handles

Every command takes a handle:

| Form | Meaning |
|---|---|
| `latest` | most recent session in the current directory's project |
| `a1b2c3d4` | session id or unambiguous prefix (searched current project, then all) |
| `/path/to.jsonl` | an explicit session file |
| `<session>/<agent>` | a subagent — addressable as its own session |

## Slicing

The point is to keep an agent's context small. Most list-producing commands accept:
`--kind`, `--role`, `--tool GLOB`, `--grep RE`, `--range A:B`, `--offset/--limit`,
`--since/--until`, `--errors-only`, `--no-thinking`, `--full/--max-chars`.

`list` filters on two independent axes:
- **time** — default window is the last 3 weeks; `--all-time`, `--since DATE`
- **scope** — current project by default; `--all-projects`, `--project DIR`

`--grep` (regex over title/prompts/cwd) searches *all* time, so it's how you find
an older session by keyword.

## Design notes

- **Thin extractor.** It counts, pairs, and slices — no judgments. Interpretation is the reading agent's job.
- **Errors vs. rejections.** A `tool_result` marked `is_error` only because the *user declined* (e.g. rejected an `AskUserQuestion` or `Edit`) is flagged `rejected` and kept out of the `errors` count, so error rates aren't inflated.
- **Subagent visibility.** A child's failures roll up into the parent's `summary` (`subagents.with_errors`/`tool_errors`) so they aren't invisible.
- **Recency window.** Sessions pile up forever, so `list` browses the last 3 weeks by default and reports (on stderr) how many older ones are hidden. Addressing a session by id always works regardless of the window.
- **Output channels.** Data on stdout (JSON / JSONL); diagnostics (notes, warnings) on stderr — keep them separate when piping stdout into a JSON parser.

## Layout

```
bb.edn
bin/claude-sessions         # bash wrapper -> bb -m introspect.core
src/introspect/
  sessions.clj              # locate / parse / resolve handles, list, subagents
  analyze.clj               # rows -> indexed events, filtering, rollups, tokens
  render.clj                # JSON / JSONL output + truncation
  core.clj                  # CLI dispatch
SKILL.md                    # agent-facing skill
```

## Session data model

`~/.claude/projects/<encoded-cwd>/<id>.jsonl` — one JSON row per line. Rows are
normalized into indexed **events** (`prompt`, `text`, `thinking`, `tool_use`,
`tool_result`, `system`, `attachment`); `tool_use.id` ↔ `tool_result.tool_use_id`
(carrying `is_error`, plus a derived `rejected` flag for user declines). Each event
carries `sidechain: true` when it happened inside a subagent. Subagents live under
`<id>/subagents/agent-<aid>.jsonl` with a sibling `.meta.json` (`agentType`,
`description`, `toolUseId` → the parent `Agent`/`Task` call). Skills are detected
from `Skill` tool_use blocks; user-typed `/slash` commands are plain prompt text.
