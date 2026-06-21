(ns introspect.core
  "claude-sessions: a thin extractor over Claude Code session files, for LLMs.

  Every command takes a session HANDLE (id-prefix | path | latest |
  session/agent-prefix) and supports slicing flags so an eval agent pulls only
  the slice it needs. Output is JSON (rollups) or JSONL (event streams)."
  (:require [introspect.sessions :as sess]
            [introspect.analyze :as az]
            [introspect.render :as r]
            [clojure.string :as str]))

(def boolean-flags #{:full :no-thinking :all :errors-only :verbose :oneline
                     :include-subagents})
(def long-flags    #{:limit :offset :max-chars})

(defn parse-args
  "Split argv into {:pos [...] :opts {...}}. Supports --flag, --key val,
  --key=val."
  [args]
  (loop [args args, pos [], opts {}]
    (if-let [a (first args)]
      (cond
        (str/starts-with? a "--")
        (let [body (subs a 2)
              [k v] (if (str/includes? body "=")
                      (str/split body #"=" 2)
                      [body nil])
              k (keyword k)]
          (cond
            (boolean-flags k) (recur (rest args) pos (assoc opts k true))
            (some? v)         (recur (rest args) pos
                                     (assoc opts k (if (long-flags k) (parse-long v) v)))
            :else             (recur (drop 2 args) pos
                                     (assoc opts k (let [nv (second args)]
                                                     (if (long-flags k) (parse-long nv) nv))))))
        :else (recur (rest args) (conj pos a) opts))
      {:pos pos :opts opts})))

(def usage
  "claude-sessions — introspect Claude Code sessions (output is JSON/JSONL)

USAGE: claude-sessions <command> [handle] [args] [flags]

  HANDLE = session-id (or prefix) | path/to.jsonl | latest | session/agent-prefix

COMMANDS
  list                       Find sessions, newest first; terse by default
                               (id,mtime,#msgs,#subagents,cwd,title — one per line)
                               --verbose  full per-session objects
                               --grep RE  regex over title/prompts/cwd
                               --all  --project DIR  --since ISO
                               --limit N (default 20)  --offset N (page)
                               (a stderr note tells you when results were capped)
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
  event     <handle> <i>     One full event by index (expands a truncated one)

GLOB matches tool names with * and ? (e.g. 'mcp__clojure-mcp__*', 'Bash').")

(defn- handle [pos] (or (second pos)
                        (throw (ex-info "This command needs a session handle." {}))))

(defn- rows-of [pos] (sess/load-rows (:path (sess/resolve-handle (handle pos)))))
(defn- events-of [pos] (az/events (rows-of pos)))

(defn run [pos opts]
  (case (first pos)
    "list"
    (let [{:keys [sessions total offset]} (sess/list-sessions opts)
          shown (count sessions)]
      ;; never silently hide sessions: if results were capped, say so
      (when (> total (+ offset shown))
        (r/print-note (format "showing %d of %d sessions (offset %d) — raise --limit, use --offset, or --grep to find more"
                              shown total offset)))
      (if (:verbose opts)
        (r/print-json sessions opts)                       ; full per-session objects
        (r/print-jsonl (map sess/compact-meta sessions) opts)))  ; terse, default

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
                 {:count 0})]
      (r/print-json (assoc base :subagents agg)))

    "transcript"
    (r/print-jsonl (az/filter-events (events-of pos) opts) opts)

    "tools"
    (r/print-json (az/tool-rollup (events-of pos) opts))

    "tool"
    (let [nm (or (nth pos 2 nil)
                 (throw (ex-info "Usage: tool <handle> <tool-name-glob>" {})))
          ;; this drill-in view defaults to FULL content
          opts (if (contains? opts :max-chars) opts (assoc opts :full true))]
      (r/print-json (az/tool-calls (events-of pos) nm opts) opts))

    "skills"
    (r/print-json (az/skill-calls (events-of pos) opts))

    "subagents"
    (r/print-json (sess/list-subagents (handle pos)))

    "tokens"
    (let [h (handle pos)
          {:keys [path]} (sess/resolve-handle h)
          rows (cond-> (sess/load-rows path)
                 ;; roll the whole session tree's cost into one accounting
                 (:include-subagents opts)
                 (into (mapcat sess/load-rows (sess/subagent-paths h))))]
      (r/print-json (az/token-rollup rows opts)))

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
  (let [{:keys [pos opts]} (parse-args args)]
    (try
      (run pos opts)
      (catch clojure.lang.ExceptionInfo e
        (r/print-err (.getMessage e) (not-empty (ex-data e)))
        (System/exit 1))
      (catch Exception e
        (r/print-err (str (.getClass e) ": " (.getMessage e)) nil)
        (System/exit 1)))))
