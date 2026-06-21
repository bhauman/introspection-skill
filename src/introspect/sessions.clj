(ns introspect.sessions
  "Locating, parsing and resolving Claude Code session files.

  Claude Code stores sessions on disk as newline-delimited JSON:

    ~/.claude/projects/<encoded-cwd>/<session-id>.jsonl          ; a session
    ~/.claude/projects/<encoded-cwd>/<session-id>/subagents/      ; its subagents
        agent-<id>.jsonl                                          ;   subagent session
        agent-<id>.meta.json                                      ;   {agentType,description,toolUseId}

  <encoded-cwd> is the absolute working directory with every '/' and '.'
  replaced by '-'."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

(declare resolve-handle)

(def projects-root
  (str (fs/path (System/getProperty "user.home") ".claude" "projects")))

(defn encode-cwd
  "Encode an absolute path the way Claude Code names its project directories."
  [path]
  (str/replace (str path) #"[/.]" "-"))

(defn current-project-dir
  "Project dir for the current working directory, or nil if it has no sessions."
  []
  (let [d (fs/path projects-root (encode-cwd (fs/cwd)))]
    (when (fs/directory? d) (str d))))

;; ---------------------------------------------------------------------------
;; Parsing
;; ---------------------------------------------------------------------------

(defn parse-line [line]
  (try (json/parse-string line true)
       (catch Exception _ {:type "_parse_error" :raw line})))

(defn load-rows
  "Read a .jsonl session file into a vector of parsed maps (file order preserved)."
  [path]
  (with-open [r (clojure.java.io/reader (str path))]
    (->> (line-seq r)
         (remove str/blank?)
         (mapv parse-line))))

(defn read-meta
  "Read a subagent's sibling .meta.json, if present."
  [jsonl-path]
  (let [m (str/replace (str jsonl-path) #"\.jsonl$" ".meta.json")]
    (when (fs/exists? m)
      (json/parse-string (slurp m) true))))

;; ---------------------------------------------------------------------------
;; Handle resolution
;; ---------------------------------------------------------------------------
;; A "handle" is what every command accepts to name a session:
;;   - a full path to a .jsonl file
;;   - a session-id (or unambiguous prefix), searched across projects
;;   - "latest" -> most recently modified session in the current project
;;   - "<session>/<agent-prefix>" -> a subagent of <session>

(defn- session-files
  "All top-level session .jsonl files under one or all project dirs."
  [{:keys [all project]}]
  (let [dirs (cond
               project [project]
               all     (filter fs/directory? (map str (fs/list-dir projects-root)))
               :else   (if-let [d (current-project-dir)]
                         [d]
                         (filter fs/directory? (map str (fs/list-dir projects-root)))))]
    (mapcat (fn [d]
              (->> (fs/glob d "*.jsonl")
                   (map str)))
            dirs)))

(defn- find-by-id
  "Resolve a session-id (or prefix) to a single .jsonl path, searching the
  current project first, then all projects. Throws on ambiguity / no match."
  [id]
  (let [hits (->> (concat (session-files {})
                          (session-files {:all true}))
                  distinct
                  (filter #(str/starts-with? (fs/file-name %) id)))
        hits (distinct hits)]
    (cond
      (empty? hits)
      (throw (ex-info (str "No session matches id/prefix: " id) {:id id}))
      (> (count hits) 1)
      (throw (ex-info (str "Ambiguous id/prefix '" id "' matches " (count hits)
                           " sessions; use a longer prefix or a path.")
                      {:id id :matches (mapv str hits)}))
      :else (first hits))))

(defn- latest-session []
  (let [d (or (current-project-dir)
              (throw (ex-info "No sessions for the current project." {})))]
    (->> (fs/glob d "*.jsonl")
         (sort-by #(fs/last-modified-time %))
         last
         str)))

(defn- resolve-subagent
  "Resolve '<parent-handle>/<agent-prefix>' to a subagent .jsonl path."
  [parent-handle agent-prefix]
  (let [parent (:path (resolve-handle parent-handle))
        sid    (str/replace (fs/file-name parent) #"\.jsonl$" "")
        dir    (fs/path (fs/parent parent) sid "subagents")
        pref   (if (str/starts-with? agent-prefix "agent-")
                 agent-prefix (str "agent-" agent-prefix))
        hits   (when (fs/directory? dir)
                 (->> (fs/glob dir "*.jsonl")
                      (filter #(str/starts-with? (fs/file-name %) pref))
                      (map str)))]
    (cond
      (empty? hits)
      (throw (ex-info (str "No subagent '" agent-prefix "' under " sid) {}))
      (> (count hits) 1)
      (throw (ex-info (str "Ambiguous subagent prefix '" agent-prefix "'")
                      {:matches hits}))
      :else (first hits))))

(defn resolve-handle
  "Resolve a handle string into {:path :kind :id [:parent]}.
  :kind is :session or :subagent."
  [handle]
  (cond
    (and (str/ends-with? handle ".jsonl") (fs/exists? handle))
    {:path (str (fs/absolutize handle))
     :kind (if (str/includes? (str handle) "/subagents/") :subagent :session)
     :id   (str/replace (fs/file-name handle) #"\.jsonl$" "")}

    (= handle "latest")
    (let [p (latest-session)]
      {:path p :kind :session :id (str/replace (fs/file-name p) #"\.jsonl$" "")})

    ;; subagent handle: parent/agent-prefix  (parent itself may contain no '/')
    (and (str/includes? handle "/") (not (fs/exists? handle)))
    (let [idx          (str/last-index-of handle "/")
          parent       (subs handle 0 idx)
          agent-prefix (subs handle (inc idx))
          p            (resolve-subagent parent agent-prefix)]
      {:path p :kind :subagent :id (str/replace (fs/file-name p) #"\.jsonl$" "")
       :parent parent})

    :else
    (let [p (find-by-id handle)]
      {:path p :kind :session :id (str/replace (fs/file-name p) #"\.jsonl$" "")})))

;; ---------------------------------------------------------------------------
;; Listing
;; ---------------------------------------------------------------------------

(defn- row-text
  "Best-effort plain text of a message row (for grep/preview)."
  [row]
  (let [c (get-in row [:message :content])]
    (cond
      (string? c) c
      (sequential? c) (->> c (keep #(or (:text %) (:thinking %))) (str/join " "))
      :else "")))

(defn session-meta
  "Cheap header for one session file: id, title, prompts, counts, tokens."
  [path]
  (let [rows  (load-rows path)
        types (frequencies (map :type rows))
        title (->> rows (filter #(= "ai-title" (:type %))) last :aiTitle)
        lastp (->> rows (filter #(= "last-prompt" (:type %))) last :lastPrompt)
        firstp (->> rows
                    (filter #(and (= "user" (:type %))
                                  (string? (get-in % [:message :content]))))
                    first row-text)
        usage (->> rows (filter #(= "assistant" (:type %)))
                   (keep #(get-in % [:message :usage])))
        cwd   (->> rows (keep :cwd) first)
        sid   (str/replace (fs/file-name path) #"\.jsonl$" "")
        subdir (fs/path (fs/parent path) sid "subagents")]
    {:id sid
     :path (str path)
     :title (or title "")
     :cwd cwd
     :first_prompt (or firstp "")
     :last_prompt (or lastp "")
     :mtime (str (fs/last-modified-time path))
     :messages (count rows)
     :by_type types
     :tokens {:input  (reduce + 0 (keep :input_tokens usage))
              :output (reduce + 0 (keep :output_tokens usage))}
     :subagents (if (fs/directory? subdir)
                  (count (fs/glob subdir "*.jsonl")) 0)}))

(def default-list-limit
  "Page size for `list` when --limit is not given. Newest first; page with
  --offset, or raise --limit for more."
  20)

(defn list-sessions
  "List session headers, newest first. opts: :all :project :grep :since
  :limit :offset. :limit defaults to `default-list-limit`.

  Reading a header parses the whole file, so when there is no content
  :grep we page on the (cheap) mtime-sorted file list *before* reading."
  [{:keys [grep since offset] :as opts}]
  (let [limit (or (:limit opts) default-list-limit)
        re    (when grep (re-pattern grep))
        files (->> (session-files opts)
                   ;; cheap mtime filter + sort without opening files
                   (filter #(or (nil? since)
                                (>= (compare (str (fs/last-modified-time %)) since) 0)))
                   (sort-by #(str (fs/last-modified-time %)))
                   reverse)
        files (if re                         ; grep needs contents -> can't pre-page
                files
                (cond->> files offset (drop offset) limit (take limit)))
        metas (map session-meta files)
        metas (cond->> metas
                re     (filter #(some->> [(:title %) (:first_prompt %) (:last_prompt %)]
                                         (keep identity)
                                         (some (fn [s] (re-find re s)))))
                (and re offset) (drop offset)
                (and re limit)  (take limit))]
    (vec metas)))

(defn list-subagents
  "List the subagent sessions of a (parent) session handle."
  [handle]
  (let [{:keys [path]} (resolve-handle handle)
        sid  (str/replace (fs/file-name path) #"\.jsonl$" "")
        dir  (fs/path (fs/parent path) sid "subagents")]
    (when (fs/directory? dir)
      (->> (fs/glob dir "*.jsonl")
           (map str)
           (sort)
           (mapv (fn [p]
                   (let [m    (read-meta p)
                         rows (load-rows p)
                         aid  (-> (fs/file-name p)
                                  (str/replace #"^agent-" "")
                                  (str/replace #"\.jsonl$" ""))
                         assistants (filter #(= "assistant" (:type %)) rows)
                         usage (keep #(get-in % [:message :usage]) assistants)
                         tool-uses (->> assistants
                                        (mapcat #(get-in % [:message :content]))
                                        (filter #(= "tool_use" (:type %)))
                                        count)]
                     {:agent_id aid
                      :handle (str sid "/" (subs aid 0 (min 8 (count aid))))
                      :type (:agentType m)
                      :description (:description m)
                      :tool_use_id (:toolUseId m)
                      :path p
                      :messages (count rows)
                      :assistant_turns (count assistants)
                      :tool_uses tool-uses
                      :tokens {:input  (reduce + 0 (keep :input_tokens usage))
                               :output (reduce + 0 (keep :output_tokens usage))}})))))))
