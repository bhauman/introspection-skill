---
name: claude-sessions
description: >-
  Introspect completed Claude Code sessions and their subagents for evals.
  Use when asked to evaluate or analyze how tools/skills/subagents were used in
  a session, judge or tune a tool's or skill's description from real usage, see
  which tools a session called (and their errors), inspect a session transcript,
  account for token cost, or examine a subagent's behavior. Triggers: "how was
  <tool> used", "did the <skill> skill trigger correctly", "review the transcript
  / tool usage of session X", "what did this subagent do", "why did Claude pick
  tool A over B". The CLI emits JSON/JSONL for an agent to read — there is no
  human UI.
---

# Claude Session Introspection

A thin extractor over Claude Code's on-disk session logs. It normalizes the raw
JSONL, pairs tool calls with their results, and lets you **slice** so you pull
only the part you need into context. It makes no judgements — counts, pairs,
filters. *You* do the evaluating.

## Run it

```
bb --config <skill-dir>/bb.edn -m introspect.core <command> [handle] [args] [flags]
# or, equivalently, the wrapper:
<skill-dir>/bin/claude-sessions <command> [handle] [args] [flags]
```

Run `claude-sessions help` for the full surface.

## Handle — every command takes one

| Form | Meaning |
|---|---|
| `latest` | most recently modified session in the current working dir's project |
| `a02053ab` | a session-id or unambiguous **prefix** (searched current project, then all) |
| `/abs/path/to.jsonl` | an explicit session file |
| `<session>/<agent-prefix>` | a **subagent** of `<session>` (e.g. `dd362f68/a694edb`) |

Subagents are first-class: list them with `subagents`, then point any command
(`transcript`, `tools`, `tool`, `tokens`, …) at the `session/agent` handle.

## Workflow: start cheap, then drill in

1. **`list`** — find the session, **newest first**, **terse by default** (one
   JSON line each: id, mtime, #msgs, #subagents, cwd, title). `--verbose` for the
   full per-session objects. Searches with **`--grep RE`** (regex over
   title/prompts **and cwd** — reaches past the page, so it's how you find an
   older session). Scope with `--all` / `--project DIR` / `--since ISO`. Defaults
   to the current project and **20 sessions** (`--limit`); page with `--offset N`.
   **When results are capped, a `note:` line on stderr tells you** (`showing 20 of
   62 …`) so a session is never silently hidden.
2. **`summary <h>`** — one cheap call: title, cwd, models, event/message counts,
   per-tool call counts, **`tool_errors` (genuine faults) and `tool_rejected`
   (user declines)** kept separate, skills used, token totals, **plus a
   `subagents` aggregate (`count`, `with_errors`, `tool_errors`, …) so child
   failures show up even when the parent is clean**. Read this first to decide
   where to look.
3. **Drill in** with a slice (never dump a whole transcript blind):
   - `tools <h>` — per-tool rollup `{calls, errors, rejected, mcp, first_i, last_i}`;
     `--name GLOB`. `errors` = real faults only; `rejected` = user-declined calls.
   - `tool <h> <glob>` — **every call** to matching tool(s), tool_use paired with
     its tool_result, **full content** (this is the view for judging whether a
     tool's description led to correct usage). `--errors-only`, `--grep`,
     `--offset/--limit`. Each result carries `is_error` and `rejected`.
   - `skills <h>` — each `Skill` invocation with `{skill, args}` and the user
     prompt that preceded it (for trigger-accuracy evals). `--name`.
   - `transcript <h>` — normalized JSONL event stream, one event per line.
   - `tokens <h>` — `--by total|message|model`. Rollups add `input_side_total`
     and `cache_read_pct` so cache-vs-fresh cost is legible without pricing.
     **`--include-subagents`** rolls up the whole session tree (e.g. `--by model`
     then shows the Opus parent and any Haiku subagents separately).
   - `subagents <h>` — list subagent sessions with per-agent `assistant_turns`,
     `tool_uses`, `tool_errors`, `tool_rejected`, `started`/`ended`, and tokens
     (incl. `cache_read_pct`); `handle` is the short `id/agent` form — dive in with it.
   - `event <h> <i>` — one full event by index (to expand something truncated).

## Slicing flags (the whole point — keep your context small)

Apply to `transcript` (and most accept a subset elsewhere):

- `--kind tool_use,tool_result,prompt,text,thinking,system` — comma list of event kinds
- `--role assistant|user|system`
- `--tool GLOB` — keep only matching tool calls **and their paired results** (`Bash`, `mcp__clojure-mcp__*`)
- `--range A:B` — event-index window (half-open); or `--offset/--limit N`
- `--grep RE` — regex over text / tool input / result content
- `--no-thinking` — drop reasoning blocks · `--errors-only` — only `is_error` tool
  results (works on `transcript` and `tool`; rejections are flagged via `rejected`)
- `--since/--until ISO` — timestamp bounds
- `--full` / `--max-chars N` — content length. Default truncates strings to
  ~1500 chars with `…[+N chars]`. `tool` and `event` default to full.

`i` (event index) is stable file order; `--range`/`--offset` and `event <h> <i>`
all speak it, so you can page a transcript and then expand any single event.

## Eval recipes

- **Judge a tool's description from usage**: `tool <h> 'Bash' --errors-only` →
  read the failing inputs + results, spot misuse / repeated errors → propose a
  description tweak. (Drop `--errors-only` to see all calls.)
- **Was a skill triggered appropriately?**: `skills <h>` → compare each
  `preceding_prompt` against where the skill *should* have fired (false neg/pos).
- **Compare two tools' usage**: `tools <h> --name 'mcp__clojure-mcp__*'` then
  `tools <h> --name 'Edit'` to see which the model reached for.
- **Audit a subagent**: `subagents <h>` → `transcript <h>/<agent> --kind tool_use`
  → `tool <h>/<agent> <name>` for the calls that matter.
- **All failures in a session**: `transcript <h> --errors-only` (each tool_result
  carries `rejected` so you can tell genuine faults from user declines).

## Data model (what's underneath)

A session is `~/.claude/projects/<encoded-cwd>/<id>.jsonl`, one JSON row per
line. Rows are normalized into indexed **events** (`prompt`, `text`, `thinking`,
`tool_use`, `tool_result`, `system`, `attachment`); sidecar rows (`ai-title`,
`mode`, `permission-mode`, `last-prompt`, …) are dropped from the stream but feed
`list`/`summary`. `tool_use.id` ↔ `tool_result.tool_use_id` (carries `is_error`,
and a derived `rejected` flag when the error is a user decline — "tool use was
rejected" — rather than a real fault, so error rates aren't inflated).
Subagents live in `<id>/subagents/agent-<aid>.jsonl` with a sibling
`.meta.json` (`agentType`, `description`, `toolUseId` → the parent `Agent`/`Task`
call). Skills are detected from `Skill` tool_use blocks (`{skill, args}`);
user-typed `/slash` commands are plain prompt text, not skill invocations.
