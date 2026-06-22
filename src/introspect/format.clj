(ns introspect.format
  "Compact, scannable text rendering for the rollup/report commands.

  Agents introspecting raw sessions by hand naturally produce dense tabular
  text (histograms, aligned columns) — not JSON — when reading for themselves,
  and that shape is far lighter than pretty JSON. These renderers emit that
  shape. JSON stays available on every command via --json for re-processing."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [introspect.render :as r]))

;; ---- small text helpers ---------------------------------------------------

(defn- spaces [n] (apply str (repeat (max 0 n) \space)))
(defn- padl [v n] (let [s (str v)] (str (spaces (- n (count s))) s)))
(defn- padr [v n] (let [s (str v)] (str s (spaces (- n (count s))))))

(defn- clip [v n]
  (let [s (str v)]
    (if (> (count s) n) (str (subs s 0 (max 0 (dec n))) "…") s)))

(defn- one-line [s] (-> (str s) (str/replace #"\s+" " ") str/trim))

(defn- cut [s max] (if (and max (string? s)) (r/truncate-str s max) s))

(defn- ts16 [ts] (when (string? ts) (subs ts 0 (min 16 (count ts)))))  ; yyyy-MM-ddThh:mm

(defn- human [n]
  (let [n (or n 0)]
    (cond
      (>= n 1000000) (format "%.2fM" (/ (double n) 1e6))
      (>= n 1000)    (format "%.1fk" (/ (double n) 1e3))
      :else          (str n))))

(defn- basename [path] (when (seq (str path)) (last (str/split (str path) #"/"))))

;; ---- rollup renderers -----------------------------------------------------

(defn- tool-hist
  "Indented `calls name` histogram from a [{:name :calls}] vector."
  [tools]
  (let [cw (apply max 1 (map #(count (str (:calls %))) tools))]
    (str/join "\n" (for [t tools] (str "  " (padl (:calls t) cw) " " (:name t))))))

(defn tools
  "Per-tool rollup -> aligned table."
  [rollup]
  (if (empty? rollup)
    "(no tool calls)"
    (let [cw (apply max 5 (map #(count (str (:calls %))) rollup))]
      (str/join "\n"
                (cons (str (padl "calls" cw) "  err  rej  tool")
                      (for [t rollup]
                        (str (padl (:calls t) cw)
                             "  " (padl (:errors t) 3)
                             "  " (padl (:rejected t) 3)
                             "  " (:name t))))))))

(defn summary [m]
  (let [tok (:tokens m)
        sub (:subagents m)
        title (clip (or (not-empty (:title m)) "(untitled)") 70)]
    (str/join
     "\n"
     (cond-> [(str "session  " (clip (:id m) 8) "  \"" title "\"")
              (str "cwd      " (:cwd m)
                   (when (not-empty (:gitBranch m)) (str "   branch " (:gitBranch m))))
              (str "models   " (str/join ", " (:models m)))
              (str "span     " (ts16 (:started m)) " → " (ts16 (:ended m))
                   "   " (:messages m) " msgs, " (:events m) " events")
              (str "tools    " (:tool_calls m) " calls   "
                   (:tool_errors m) " err   " (:tool_rejected m) " rej")]
       (seq (:tools m))  (conj (tool-hist (:tools m)))
       (seq (:skills m)) (conj (str "skills   " (str/join ", " (:skills m))))
       (and sub (pos? (or (:count sub) 0)))
       (conj (str "subagents " (:count sub) "  (" (:with_errors sub) " with errors, "
                  (:tool_errors sub) " tool_errors)"))
       tok (conj (str "tokens   output " (human (:output tok))
                      "   input-side " (human (:input_side_total tok))
                      "   cache-read " (:cache_read_pct tok) "%"))))))

(defn subagents [rows]
  (if (empty? rows)
    "(no subagents)"
    (let [hw (apply max 6 (map #(count (str (:handle %))) rows))
          tw (apply max 4 (map #(count (str (:type %))) rows))]
      (str/join
       "\n"
       (cons (str (padr "handle" hw) "  " (padr "type" tw) "  turns  tools  err  rej    out  description")
             (for [a rows]
               (str (padr (:handle a) hw)
                    "  " (padr (:type a) tw)
                    "  " (padl (:assistant_turns a) 5)
                    "  " (padl (:tool_uses a) 5)
                    "  " (padl (:tool_errors a) 3)
                    "  " (padl (:tool_rejected a) 3)
                    "  " (padl (human (get-in a [:tokens :output])) 5)
                    "  " (clip (one-line (:description a)) 52))))))))

(defn skills [rows]
  (if (empty? rows)
    "(no skill invocations)"
    (str/join
     "\n"
     (for [s rows]
       (str "i=" (:i s) "  " (:skill s)
            (cond (nil? (:launched s)) ""
                  (:launched s) "  ✓launched"
                  :else "  ✗failed")
            (when (:args s) (str "  args=" (clip (one-line (json/generate-string (:args s))) 60)))
            "\n    ← " (clip (one-line (:preceding_prompt s)) 140))))))

(defn- tok-line [m]
  (str "output " (human (:output m))
       "   input " (human (:input m))
       "   cache-read " (human (:cache_read m))
       (when (:cache_read_pct m) (str " (" (:cache_read_pct m) "%)"))
       (when (:input_side_total m) (str "   input-side " (human (:input_side_total m))))))

(defn tokens [data]
  (if (map? data)
    (str "totals (" (:messages data) " msgs)  " (tok-line data))
    (str/join "\n"
              (for [m data]
                (if (:messages m)                       ; --by model
                  (str (padr (clip (:model m) 22) 22) "  "
                       (padl (:messages m) 5) " msgs  " (tok-line m))
                  (str (ts16 (:ts m)) "  "              ; --by message
                       (padr (clip (or (:model m) "") 20) 20) "  " (tok-line m)))))))

(defn tool
  "Content pairs (use + result) -> readable blocks. `max` nil = full content."
  [calls max]
  (if (empty? calls)
    "(no matching calls)"
    (str/join
     "\n\n"
     (for [c calls]
       (let [r (:result c)
             status (cond (nil? r) "(no result)"
                          (:rejected r) "REJECTED"
                          (:is_error r) "ERROR"
                          :else "ok")]
         (str "i=" (:i c) "  " (:name c) "  " status (when (:sidechain c) "  [sidechain]")
              "\n  input:  " (cut (json/generate-string (:input c)) max)
              (when r (str "\n  result: " (cut (:content r) max)))))))))

(defn sessions-list [metas]
  (if (empty? metas)
    "(no sessions)"
    (str/join
     "\n"
     (cons (str (padr "id" 8) "  " (padr "mtime" 16) "  msgs  sub  "
                (padr "project" 18) "  title")
           (for [m metas]
             (str (padr (clip (:id m) 8) 8)
                  "  " (padr (ts16 (:mtime m)) 16)
                  "  " (padl (:messages m) 4)
                  "  " (padl (:subagents m) 3)
                  "  " (padr (clip (basename (:cwd m)) 18) 18)
                  "  " (clip (one-line (:title m)) 70)))))))
