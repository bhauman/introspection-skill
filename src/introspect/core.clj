(ns introspect.core
  "claude-sessions: a thin extractor over Claude Code session files, for LLMs.

  Every command takes a session HANDLE (id-prefix | path | latest |
  session/agent-prefix) and supports slicing flags so an eval agent pulls only
  the slice it needs. Output is compact text by default; --json emits JSON
  (rollups) / JSONL (event streams) for piping to jq."
  (:require [introspect.sessions :as sess]
            [introspect.analyze :as az]
            [introspect.render :as r]
            [introspect.format :as fmt]
            [clojure.string :as str]))

(def boolean-flags #{:full :no-thinking :all-projects :errors-only :verbose
                     :oneline :include-subagents :all-time :json})
(def value-flags   #{:project :grep :since :until :limit :offset :max-chars
                     :kind :role :tool :range :name :by})
(def long-flags    #{:limit :offset :max-chars})

(defn parse-args
  "Split argv into {:pos [...] :opts {...} :unknown [...]}. Supports --flag,
  --key val, --key=val. An unrecognized --flag is treated as a boolean (it does
  NOT swallow the next arg) and recorded in :unknown so the caller can warn —
  this keeps a typo or stale flag from silently eating a real value."
  [args]
  (loop [args args, pos [], opts {}, unknown []]
    (if-let [a (first args)]
      (cond
        (str/starts-with? a "--")
        (let [body  (subs a 2)
              [k v] (if (str/includes? body "=") (str/split body #"=" 2) [body nil])
              k     (keyword k)
              long? (long-flags k)]
          (cond
            (some? v)         (recur (rest args) pos
                                     (assoc opts k (if long? (parse-long v) v)) unknown)
            (boolean-flags k) (recur (rest args) pos (assoc opts k true) unknown)
            (value-flags k)   (recur (drop 2 args) pos
                                     (assoc opts k (let [nv (second args)]
                                                     (if long? (parse-long nv) nv)))
                                     unknown)
            :else             (recur (rest args) pos (assoc opts k true)
                                     (conj unknown (str "--" body)))))
        :else (recur (rest args) (conj pos a) opts unknown))
      {:pos pos :opts opts :unknown unknown})))

(def usage
  "claude-sessions — introspect Claude Code sessions

USAGE: claude-sessions <command> [handle] [args] [flags]

  HANDLE = session-id (or prefix) | path/to.jsonl | latest | session/agent-prefix

OUTPUT: compact, scannable text by default. Add --json for machine-readable
        JSON (rollups) / JSONL (list, transcript) you can pipe to jq. Diagnostic
        notes go to stderr — don't 2>&1 when piping --json into a parser.

COMMANDS
  list                       Find sessions, newest first; terse by default
                               (id,mtime,#msgs,#subagents,cwd,title — one per line)
                               TIME:  default window last 3 weeks (older hidden; stderr note)
                                      --all-time (no age cap)  --since ISO (set window)
                               SCOPE: current project by default
                                      --all-projects  --project DIR
                               --grep RE   regex over title/prompts/cwd (searches ALL time)
                               --verbose   full per-session objects   --limit/--offset N
  summary   <handle>         Cheap structural map of a session
  transcript <handle>        Normalized JSONL event stream
                               --kind tool_use,prompt,...  --role  --tool GLOB
                               --range A:B  --offset/--limit N  --grep RE
                               --no-thinking  --since/--until ISO  --full/--max-chars N
  tools     <handle>         Per-tool rollup (calls, errors, rejected)   --name GLOB
  tool      <handle> <glob>  Every call to matching tool(s), use+result paired (full content)
                               --errors-only  --grep RE  --offset/--limit N  --max-chars N
  skills    <handle>         Each Skill invocation + preceding prompt   --name GLOB
  subagents <handle>         List subagent sessions (dive in with handle session/agent-id)
  tokens    <handle>         Token accounting   --by total|message|model
                               --include-subagents  roll up parent + all subagents
  event     <handle> <i>     One full event by index — i is the `i` field that
                               transcript/tool/tools emit (expands a truncated one)

GLOB matches tool names with * and ? (e.g. 'mcp__clojure-mcp__*', 'Bash').")

(defn- handle [pos] (or (second pos)
                        (throw (ex-info "This command needs a session handle." {}))))

(defn- rows-of [pos] (sess/load-rows (:path (sess/resolve-handle (handle pos)))))
(defn- events-of [pos] (az/events (rows-of pos)))

(defn run [pos opts]
  (case (first pos)
    "list"
    (let [{:keys [sessions total offset older since]} (sess/list-sessions opts)
          shown (count sessions)]
      ;; never silently hide sessions
      (when (pos? (or older 0))
        (r/print-note (format "%d older session(s) hidden (before %s) — use --since DATE, --grep, or --all-time"
                              older since)))
      (when (> total (+ offset shown))
        (r/print-note (format "showing %d of %d in range (offset %d) — use --offset/--limit"
                              shown total offset)))
      (cond
        (:verbose opts) (r/print-json sessions opts)                          ; full objects
        (:json opts)    (r/print-jsonl (map sess/compact-meta sessions) opts) ; compact JSONL
        :else           (println (fmt/sessions-list (map sess/compact-meta sessions)))))

    "summary"
    (let [h (handle pos)
          {:keys [path]} (sess/resolve-handle h)
          base (az/summary (sess/load-rows path) path)
          subs (sess/list-subagents h)
          agg  (if (seq subs)
                 {:count (count subs)
                  :with_errors (count (filter #(pos? (or (:tool_errors %) 0)) subs))
                  :tool_uses (reduce + 0 (map #(or (:tool_uses %) 0) subs))
                  :tool_errors (reduce + 0 (map #(or (:tool_errors %) 0) subs))
                  :tool_rejected (reduce + 0 (map #(or (:tool_rejected %) 0) subs))}
                 {:count 0})
          data (assoc base :subagents agg)]
      (if (:json opts) (r/print-json data) (println (fmt/summary data))))

    "transcript"
    (r/print-jsonl (az/filter-events (events-of pos) opts) opts)

    "tools"
    (let [data (az/tool-rollup (events-of pos) opts)]
      (if (:json opts) (r/print-json data) (println (fmt/tools data))))

    "tool"
    (let [nm (or (nth pos 2 nil)
                 (throw (ex-info "Usage: tool <handle> <tool-name-glob>" {})))
          ;; this drill-in view defaults to FULL content
          opts (if (contains? opts :max-chars) opts (assoc opts :full true))
          data (az/tool-calls (events-of pos) nm opts)]
      (if (:json opts)
        (r/print-json data opts)
        (println (fmt/tool data (r/max-chars-of opts)))))

    "skills"
    (let [data (az/skill-calls (events-of pos) opts)]
      (if (:json opts) (r/print-json data) (println (fmt/skills data))))

    "subagents"
    (let [data (sess/list-subagents (handle pos))]
      (if (:json opts) (r/print-json data) (println (fmt/subagents data))))

    "tokens"
    (let [h (handle pos)
          {:keys [path]} (sess/resolve-handle h)
          rows (cond-> (sess/load-rows path)
                 ;; roll the whole session tree's cost into one accounting
                 (:include-subagents opts)
                 (into (mapcat sess/load-rows (sess/subagent-paths h))))
          data (az/token-rollup rows opts)]
      (if (:json opts) (r/print-json data) (println (fmt/tokens data))))

    "event"
    (let [i (or (some-> (nth pos 2 nil) parse-long)
                (throw (ex-info "Usage: event <handle> <index>" {})))
          ev (nth (events-of pos) i nil)]
      (if ev
        (r/print-json ev (assoc opts :full (not (contains? opts :max-chars))))
        (throw (ex-info (str "No event at index " i) {}))))

    (nil "help" "--help" "-h")
    (println usage)

    (throw (ex-info (str "Unknown command: " (first pos)) {}))))

(defn -main [& args]
  (let [{:keys [pos opts unknown]} (parse-args args)]
    (when (seq unknown)
      (r/print-note (str "ignored unknown flag(s): " (str/join " " unknown)
                         " — run `claude-sessions help`")))
    (try
      (run pos opts)
      (catch clojure.lang.ExceptionInfo e
        (r/print-err (.getMessage e) (not-empty (ex-data e)))
        (System/exit 1))
      (catch Exception e
        (r/print-err (str (.getClass e) ": " (.getMessage e)) nil)
        (System/exit 1)))))
