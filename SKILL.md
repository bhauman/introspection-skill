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
  tool A over B". The CLI emits compact text for an agent to read (--json for
  machine-readable JSON/JSONL) — there is no human UI.
---

# Claude Session Introspection

A thin extractor over Claude Code's on-disk session logs (`~/.claude/projects/`).
It normalizes the raw JSONL, pairs every tool call with its result, and lets you
**slice** so you pull only what you need into context. It makes no judgements —
counts, pairs, filters; *you* do the evaluating.

## Run it

```
<skill-dir>/bin/claude-sessions <command> [handle] [flags]
```

**Discover the whole surface — commands, handle forms (incl. subagents), and
every slicing flag — from the CLI itself:**

```
<skill-dir>/bin/claude-sessions help
```

Then start cheap and drill in: `list` to find a session → `summary <h>` for a
structural map → `tool` / `transcript` / `skills` / `subagents` / `tokens` for
the slice that matters.

## Worth knowing (not in `help`)

- **Output channels.** Commands print **compact, scannable text** by default —
  read it directly. Add **`--json`** for machine-readable JSON (rollups) / JSONL
  (`list`, `transcript`) to pipe into `jq`. Diagnostics print to **stderr** —
  don't `2>&1` when piping `--json` into a parser.
- **Errors vs. rejections.** A `tool_result` flagged `is_error` only because the
  *user declined* is reported as `rejected`, not `errors`, so error rates aren't
  inflated. Both `tool` results and the `tools`/`summary` rollups keep them apart.
- **Subagents are first-class.** Address one as `<session>/<agent>` and point any
  command at it as if it were its own session.
