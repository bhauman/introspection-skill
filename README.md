# claude-sessions

A Babashka CLI that introspects **Claude Code session logs** (and their
subagent sessions) for evaluation work — judging how tools, skills and
subagents were actually used so you can tune their descriptions.

Output is **JSON / JSONL for an LLM to read**; there is no human UI. The CLI is
a *thin extractor*: it normalizes the raw JSONL, pairs tool calls with results,
and slices — all interpretation is left to the reading agent. The companion
[`SKILL.md`](./SKILL.md) teaches an agent how to drive it.

## Requirements

[Babashka](https://babashka.org) (`bb`). No other dependencies — `cheshire`,
`babashka.fs` and the CLI parser ship with bb.

## Use

```bash
bin/claude-sessions help                       # full command surface
bin/claude-sessions list                       # sessions in the current project
bin/claude-sessions summary latest             # cheap structural map
bin/claude-sessions tools <handle>             # per-tool rollup (calls, errors)
bin/claude-sessions tool <handle> 'Bash'       # every Bash call, input+result paired
bin/claude-sessions skills <handle>            # each skill firing + preceding prompt
bin/claude-sessions transcript <handle> --kind tool_use --range 0:40
bin/claude-sessions subagents <handle>         # then dive in: <handle>/<agent-id>
bin/claude-sessions tokens <handle> --by model
```

A **handle** is `latest`, a session-id (or prefix), a path to a `.jsonl`, or
`<session>/<agent-prefix>` for a subagent. Every command slices: `--kind`,
`--tool`, `--role`, `--grep`, `--range A:B`, `--offset/--limit`, `--since/--until`,
`--errors-only`, `--no-thinking`, `--full/--max-chars`.

The CLI works from any directory. `list` defaults to the current project and the
last 3 weeks; widen scope with `--all-projects` / `--project DIR` and time with
`--all-time` / `--since DATE`. `--grep` searches all time.

## Layout

```
bb.edn
bin/claude-sessions       # bash wrapper -> bb -m introspect.core
src/introspect/
  sessions.clj              # locate / parse / resolve handles, list, subagents
  analyze.clj               # rows -> indexed events, filtering, rollups, tokens
  render.clj                # JSON / JSONL output + truncation
  core.clj                  # CLI dispatch
SKILL.md                    # agent-facing skill
```

## Session data model

`~/.claude/projects/<encoded-cwd>/<id>.jsonl` — one JSON row per line. Rows are
normalized into indexed events (`prompt`, `text`, `thinking`, `tool_use`,
`tool_result`, `system`, `attachment`); `tool_use.id` ↔ `tool_result.tool_use_id`
(carries `is_error`). Subagents live under `<id>/subagents/agent-<aid>.jsonl`
with a sibling `.meta.json` (`agentType`, `description`, `toolUseId`).
