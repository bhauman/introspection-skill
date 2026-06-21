(ns introspect.render
  "Output. Everything an agent reads is JSON. Rollups print one JSON value;
  event streams print JSONL (one event per line) for cheap slicing/grepping.

  Truncation keeps the eval agent's context small: long strings are cut to
  :max-chars with a `…[+N chars]` marker. Pass :full to disable."
  (:require [cheshire.core :as json]
            [clojure.walk :as walk]))

(def default-max-chars 1500)

(defn truncate-str [s max]
  (if (and (string? s) (> (count s) max))
    (str (subs s 0 max) "…[+" (- (count s) max) " chars]")
    s))

(defn truncate
  "Recursively truncate every string in a value to `max` chars.
  When max is nil (or :full requested upstream), returns x unchanged."
  [x max]
  (if (nil? max)
    x
    (walk/postwalk #(if (string? %) (truncate-str % max) %) x)))

(defn- max-chars [{:keys [full max-chars]}]
  (cond full nil
        max-chars max-chars
        :else default-max-chars))

(defn print-json
  "Print one JSON value (pretty). opts may carry :full / :max-chars."
  ([x] (print-json x nil))
  ([x opts]
   (println (json/generate-string (truncate x (max-chars opts)) {:pretty true}))))

(defn print-jsonl
  "Print a collection as JSONL — one compact JSON object per line."
  ([coll] (print-jsonl coll nil))
  ([coll opts]
   (let [m (max-chars opts)]
     (doseq [x coll]
       (println (json/generate-string (truncate x m)))))))

(defn print-err [msg data]
  (binding [*out* *err*]
    (println (json/generate-string (cond-> {:error msg} data (assoc :data data))))))

(defn print-note
  "Informational diagnostic to stderr, so stdout stays pure data."
  [msg]
  (binding [*out* *err*]
    (println (str "note: " msg))))
