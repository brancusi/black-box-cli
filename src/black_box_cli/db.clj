(ns black-box-cli.db
  "SQLite through the system `sqlite3` (docs/DESIGN.md, decision 1): SQL goes in on stdin, rows come back as JSON.
  Values are bound with `.parameter set`, each written as a hex literal, so no value is ever pasted into SQL and
  nothing needs quoting."
  (:refer-clojure :exclude [run!])
  (:require
   [babashka.process :as process]
   [black-box-cli.out :as out]
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   (java.io StringReader)
   (java.nio.charset StandardCharsets)))

(def sqlite3
  "The sqlite3 to run: macOS ships one with FTS5."
  "/usr/bin/sqlite3")

(defn- hex [^String s]
  (let [bs (.getBytes s StandardCharsets/UTF_8)
        sb (StringBuilder. (* 2 (alength bs)))]
    (doseq [b bs]
      (.append sb (format "%02x" (bit-and b 0xff))))
    (str sb)))

(defn literal
  "`v` as an SQL literal that needs no quoting: text as a hex blob cast to text, numbers as they are, nil as NULL."
  [v]
  (cond
    (nil? v) "NULL"
    (boolean? v) (if v "1" "0")
    (integer? v) (str v)
    (number? v) (let [d (double v)]
                  (when (or (Double/isNaN d) (Double/isInfinite d))
                    (throw (ex-info "not a finite number" {:value v})))
                  (str d))
    :else (str "CAST(X'" (hex (str v)) "' AS TEXT)")))

(def ^:private trust
  "The index's triggers keep the full-text table in step with its rows. A sqlite3 built with trusted_schema off
  (macOS 15's) refuses triggers that write to a virtual table, so each connection turns it on: the index is this
  CLI's own file, never one from elsewhere."
  "PRAGMA trusted_schema = ON;\n")

(defn- param-lines [params]
  (apply str (map (fn [[k v]] (str ".parameter set :" (name k) " \"" (literal v) "\"\n")) params)))

(defn run!
  "Runs `sql` (one or more statements) against the database at `path` with `params` ({:name value}, used in the
  SQL as `:name`). Returns the result of every statement that returned rows, in order, each a vector of maps with
  keyword keys. A failure is error.code index-error."
  ([path sql] (run! path sql {}))
  ([path sql params]
   (let [{:keys [exit out err]} (try
                                  (process/sh {:in (str trust (param-lines params) sql "\n")} sqlite3 "-bail" "-json"
                                              (str path))
                                  (catch java.io.IOException _
                                    (out/fail! "no-sqlite" (str sqlite3 " isn't there")
                                               {:help ["sqlite3 comes with macOS; is this a Mac?"]})))]
     (when-not (zero? exit)
       (out/fail! "index-error" (str "sqlite3: " (str/trim (or err "")))
                  {:help ["Run `blackbox index --rebuild` to build the index again"]}))
     (vec (json/parsed-seq (StringReader. out) true)))))

(defn query
  "The rows of the last statement in `sql` that returned any, as a vector of maps; [] when none did."
  ([path sql] (query path sql {}))
  ([path sql params]
   (or (peek (run! path sql params)) [])))

(defn one
  "The first row of `query`, or nil."
  ([path sql] (one path sql {}))
  ([path sql params]
   (first (query path sql params))))
