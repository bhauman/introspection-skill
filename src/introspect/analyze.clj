(ns introspect.analyze
  "Turn raw session rows into a normalized, indexed event stream and the
  factual rollups built on top of it.

  This namespace makes no judgements: it counts, pairs and slices. All
  interpretation is left to the reading agent."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Normalization: rows -> flat, indexed events
;; ---------------------------------------------------------------------------

(def rejection-re
  "A tool_result is_error that is actually a *user decline* (rejected an
  AskUserQuestion / ExitPlanMode / Edit, etc.) rather than a tool failure.
  These should not inflate the error rate."
  #"(?i)tool use was rejected|user doesn't want to proceed")

(defn rejected-content?
  "True when a tool_result's content string is a user-decline, not a fault."
  [content]
  (boolean (and (string? content) (re-find rejection-re content))))

(defn content-str
  "Collapse a tool_result / message content value to a single string."
  [c]
  (cond
    (string? c) c
    (sequential? c) (->> c
                         (map #(cond (string? %) %
                                     (:text %) (:text %)
                                     :else (json/generate-string %)))
                         (str/join "\n"))
    (nil? c) ""
    :else (json/generate-string c)))

(defn- block->event
  "Expand one message content block into a normalized event (sans :i)."
  [row block]
  (let [base {:uuid (:uuid row)
              :parent (:parentUuid row)
              :ts (:timestamp row)
              :role (get-in row [:message :role] (:type row))
              :sidechain (boolean (:isSidechain row))}]
    (case (:type block)
      "thinking"  (assoc base :kind "thinking" :text (:thinking block))
      "text"      (assoc base :kind "text" :text (:text block))
      "tool_use"  (assoc base :kind "tool_use"
                         :name (:name block)
                         :tool_use_id (:id block)
                         :input (:input block))
      "tool_result" (let [content (content-str (:content block))
                          err     (boolean (:is_error block))]
                      (assoc base :kind "tool_result"
                             :tool_use_id (:tool_use_id block)
                             :is_error err
                             ;; a user decline, not a tool fault
                             :rejected (and err (rejected-content? content))
                             :content content))
      ;; unknown block kind: passthrough
      (assoc base :kind (or (:type block) "unknown") :block block))))

(defn events
  "Flatten rows into an indexed vector of normalized events.

  Each assistant/user message expands its content blocks; string user
  content becomes a single :prompt event. system rows become :system
  events. Sidecar metadata rows (ai-title, mode, ...) are dropped."
  [rows]
  (->> rows
       (mapcat
        (fn [row]
          (case (:type row)
            ("assistant" "user")
            (let [c (get-in row [:message :content])]
              (if (string? c)
                [{:uuid (:uuid row) :parent (:parentUuid row) :ts (:timestamp row)
                  :role (:type row) :sidechain (boolean (:isSidechain row))
                  :kind (if (= "user" (:type row)) "prompt" "text") :text c}]
                (map #(block->event row %) c)))

            "system"
            [{:uuid (:uuid row) :parent (:parentUuid row) :ts (:timestamp row)
              :role "system" :kind "system" :subtype (:subtype row)
              :durationMs (:durationMs row) :messageCount (:messageCount row)
              :text (content-str (get-in row [:message :content]))}]

            "attachment"
            [{:uuid (:uuid row) :ts (:timestamp row) :role "user"
              :kind "attachment" :attachment (:attachment row)}]

            ;; sidecar / metadata rows carry no transcript content
            nil)))
       (map-indexed (fn [i ev] (assoc ev :i i)))
       vec))

;; Carry per-message model/usage so transcript and token views can see it.
(defn message-meta
  "Map of message uuid -> {:model :usage :requestId} for assistant rows."
  [rows]
  (into {}
        (for [r rows :when (= "assistant" (:type r))]
          [(:uuid r) {:model (get-in r [:message :model])
                      :usage (get-in r [:message :usage])
                      :requestId (:requestId r)}])))

;; ---------------------------------------------------------------------------
;; Filtering / slicing  (cross-cutting across commands)
;; ---------------------------------------------------------------------------

(defn- glob->re [g]
  (-> g (str/replace #"\*" ".*") (str/replace #"\?" ".") re-pattern))

(defn matches-name? [pattern name]
  (boolean (and name (re-matches (glob->re pattern) name))))

(defn filter-events
  "Slice an event vector. opts:
    :kind '\"tool_use,prompt\"'  comma list of kinds
    :role 'assistant'
    :tool 'Bash' / 'mcp__*'      (glob; matches tool_use/tool_result by name)
    :range '10:40'  or  :offset/:limit
    :grep <regex>                over text/input/content
    :no-thinking  :errors-only   booleans
    :since/:until                ISO timestamp bounds"
  [evs {:keys [kind role tool range offset limit grep no-thinking
               errors-only since until]}]
  (let [kinds (when kind (set (str/split kind #",")))
        re    (when grep (re-pattern grep))
        ;; ids of tool_uses whose name matches :tool, so we can keep their results too
        tool-ids (when tool
                   (into #{} (comp (filter #(and (= "tool_use" (:kind %))
                                                 (matches-name? tool (:name %))))
                                   (map :tool_use_id))
                         evs))
        text-of (fn [ev] (str/join "\n" (keep identity
                                              [(:text ev)
                                               (when (:input ev) (json/generate-string (:input ev)))
                                               (:content ev)])))
        [rlo rhi] (when range
                    (let [[a b] (str/split range #":")]
                      [(when (seq a) (parse-long a))
                       (when (seq b) (parse-long b))]))]
    (cond->> evs
      kinds       (filter #(kinds (:kind %)))
      role        (filter #(= role (:role %)))
      tool        (filter #(contains? tool-ids (:tool_use_id %)))
      no-thinking (remove #(= "thinking" (:kind %)))
      errors-only (filter :is_error)
      since       (filter #(or (nil? (:ts %)) (>= (compare (:ts %) since) 0)))
      until       (filter #(or (nil? (:ts %)) (<= (compare (:ts %) until) 0)))
      rlo         (filter #(>= (:i %) rlo))
      rhi         (filter #(< (:i %) rhi))
      re          (filter #(re-find re (text-of %)))
      offset      (drop offset)
      limit       (take limit))))

;; ---------------------------------------------------------------------------
;; Tool pairing + rollups
;; ---------------------------------------------------------------------------

(defn result-index
  "Map tool_use_id -> the tool_result event."
  [evs]
  (into {} (for [e evs :when (= "tool_result" (:kind e))] [(:tool_use_id e) e])))

(defn tool-rollup
  "Per-tool factual counts. opts :name filters by glob."
  [evs {:keys [name]}]
  (let [results (result-index evs)
        uses    (filter #(= "tool_use" (:kind %)) evs)
        uses    (cond->> uses name (filter #(matches-name? name (:name %))))]
    (->> uses
         (group-by :name)
         (map (fn [[nm calls]]
                (let [res  (map #(results (:tool_use_id %)) calls)
                      errs (count (filter #(and (:is_error %) (not (:rejected %))) res))
                      rejs (count (filter :rejected res))]
                  {:name nm
                   :calls (count calls)
                   :errors errs        ; genuine tool faults only
                   :rejected rejs      ; user declines (not faults)
                   :mcp (boolean (str/starts-with? (or nm "") "mcp__"))
                   :first_i (apply min (map :i calls))
                   :last_i (apply max (map :i calls))})))
         (sort-by :calls >)
         vec)))

(defn tool-calls
  "Every call to tools matching `name` (glob), paired with its result.
  opts: :grep :offset :limit :errors-only."
  [evs name {:keys [grep offset limit errors-only]}]
  (let [results (result-index evs)
        re      (when grep (re-pattern grep))
        calls   (->> evs
                     (filter #(and (= "tool_use" (:kind %))
                                   (matches-name? name (:name %))))
                     (map (fn [u]
                            (let [r (results (:tool_use_id u))]
                              {:i (:i u)
                               :ts (:ts u)
                               :name (:name u)
                               :tool_use_id (:tool_use_id u)
                               :sidechain (:sidechain u)
                               :input (:input u)
                               :result (when r {:i (:i r)
                                                :is_error (:is_error r)
                                                :rejected (:rejected r)
                                                :content (:content r)})}))))
        calls   (cond->> calls
                  errors-only (filter #(get-in % [:result :is_error]))
                  re (filter #(re-find re (str (json/generate-string (:input %))
                                               (get-in % [:result :content]))))
                  offset (drop offset)
                  limit  (take limit))]
    (vec calls)))

(defn skill-calls
  "Each Skill tool_use, with the user prompt that preceded it for context."
  [evs {:keys [name]}]
  (let [evv     evs
        results (result-index evs)
        prev-prompt (fn [i]
                      (->> evv
                           (filter #(and (< (:i %) i) (= "prompt" (:kind %))))
                           last :text))]
    (->> evs
         (filter #(and (= "tool_use" (:kind %)) (= "Skill" (:name %))))
         (filter #(or (nil? name) (matches-name? name (get-in % [:input :skill]))))
         (map (fn [u]
                (let [r (results (:tool_use_id u))]
                  {:i (:i u)
                   :ts (:ts u)
                   :skill (get-in u [:input :skill])
                   :args (get-in u [:input :args])
                   :sidechain (:sidechain u)
                   :preceding_prompt (prev-prompt (:i u))
                   :result (when r {:is_error (:is_error r) :content (:content r)})})))
         vec)))

;; ---------------------------------------------------------------------------
;; Tokens
;; ---------------------------------------------------------------------------

(defn- usage-row [u]
  {:input (:input_tokens u)
   :output (:output_tokens u)
   :cache_read (:cache_read_input_tokens u)
   :cache_creation (:cache_creation_input_tokens u)})

(defn token-rollup
  "Token accounting. opts :by 'total'|'message'|'tool'."
  [rows {:keys [by]}]
  (let [ass   (filter #(= "assistant" (:type %)) rows)
        usages (keep #(when-let [u (get-in % [:message :usage])]
                        (assoc (usage-row u)
                               :uuid (:uuid %)
                               :model (get-in % [:message :model])
                               :ts (:timestamp %)))
                     ass)
        sum   (fn [ms] (reduce (fn [a m]
                                 (merge-with + a (select-keys m [:input :output :cache_read :cache_creation])))
                               {:input 0 :output 0 :cache_read 0 :cache_creation 0}
                               ms))
        ;; cache_read is ~10x cheaper than fresh input; surfacing the share
        ;; makes "cache dominates" legible without baking in $ pricing.
        with-pct (fn [m]
                   (let [in-side (+ (:input m) (:cache_creation m) (:cache_read m))]
                     (assoc m :input_side_total in-side
                            :cache_read_pct (if (pos? in-side)
                                              (Math/round (* 100.0 (/ (:cache_read m) in-side)))
                                              0))))]
    (case by
      "message" (vec usages)
      "model"   (->> usages (group-by :model)
                     (map (fn [[m ms]] (assoc (with-pct (sum ms)) :model m :messages (count ms))))
                     vec)
      ;; default: totals
      (assoc (with-pct (sum usages)) :messages (count usages)))))

;; ---------------------------------------------------------------------------
;; Summary
;; ---------------------------------------------------------------------------

(defn summary
  "Cheap structural map of one session."
  [rows path]
  (let [evs     (events rows)
        results (result-index evs)
        uses    (filter #(= "tool_use" (:kind %)) evs)
        res     (map #(results (:tool_use_id %)) uses)
        errs    (count (filter #(and (:is_error %) (not (:rejected %))) res))
        rejs    (count (filter :rejected res))
        skills  (->> uses (filter #(= "Skill" (:name %))) (map #(get-in % [:input :skill])))
        models  (->> rows (filter #(= "assistant" (:type %)))
                     (keep #(get-in % [:message :model])) distinct vec)
        tss     (keep :timestamp rows)]
    {:id (-> (re-find #"[^/]+$" (str path)) (str/replace #"\.jsonl$" ""))
     :path (str path)
     :title (or (->> rows (filter #(= "ai-title" (:type %))) last :aiTitle) "")
     :cwd (->> rows (keep :cwd) first)
     :gitBranch (->> rows (keep :gitBranch) first)
     :models models
     :started (first tss)
     :ended (last tss)
     :events (count evs)
     :messages (count rows)
     :by_type (frequencies (map :type rows))
     :by_kind (frequencies (map :kind evs))
     :tool_calls (count uses)
     :tool_errors errs
     :tool_rejected rejs
     :tools (->> uses (map :name) frequencies (sort-by val >)
                 (mapv (fn [[k v]] {:name k :calls v})))
     :skills (vec (distinct skills))
     :tokens (token-rollup rows {:by "total"})}))
