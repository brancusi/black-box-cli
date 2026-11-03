(ns dev
  "The REPL's workbench: the command line in-process against the fixture media_meta, reloading, the tests and the
  goldens. dev/fiddle.clj walks through it. Never point it at a real media_meta unless the owner asks (AGENTS.md)."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.golden :as golden]
   [black-box-cli.support :as support]
   [clojure.string :as str]
   [clojure.test :as t]))

(defonce ^:private session (atom nil))

(defn local
  "This REPL's --local folder (the index and cuts), made on first use and kept until `fresh!`."
  []
  (or @session (reset! session (str (fs/create-temp-dir {:prefix "bbcli-repl-"})))))

(defn fresh!
  "Starts again with an empty --local folder: no index, no cuts."
  []
  (reset! session nil)
  (local))

(defn cli
  "Runs blackbox with `args` against the fixture media_meta and this REPL's --local folder: {:exit :out :local}.
  Run (cli \"index\") once first; (fresh!) empties the folder."
  [& args]
  (assoc (support/cli* {} (concat ["--meta" support/fixture-meta "--local" (local)] args)) :local (local)))

(defn show
  "Runs blackbox with `args` like `cli`, prints its stdout as the shell would, and returns the exit code."
  [& args]
  (let [{:keys [exit out]} (apply cli args)]
    (print out)
    (flush)
    exit))

(defn reload
  "Reloads src/ and the test support after an edit (every namespace they require, too)."
  []
  (require 'black-box-cli.main 'black-box-cli.support 'black-box-cli.golden :reload-all)
  :reloaded)

(defn- test-namespaces []
  (->> (fs/glob "test" "**_test.clj")
       (map #(-> (str (fs/relativize "test" %))
                 (str/replace #"\.clj$" "")
                 (str/replace "/" ".")
                 (str/replace "_" "-")
                 symbol))
       sort))

(defn- test-ns-of
  "`ns` itself when it is a test namespace, else its -test twin (black-box-cli.flags → black-box-cli.flags-test)."
  [ns]
  (let [s (str ns)]
    (symbol (if (str/ends-with? s "-test") s (str s "-test")))))

(defn tests
  "Reloads, then runs every test namespace, or the ones named (a source namespace runs its -test twin). Returns the
  counts. `bb test` from the shell is still the truth before a commit."
  [& namespaces]
  (let [namespaces (if (seq namespaces) (map test-ns-of namespaces) (test-namespaces))]
    (reload)
    (apply require (concat namespaces [:reload]))
    (select-keys (apply t/run-tests namespaces) [:test :pass :fail :error])))

(defn goldens
  "Which golden steps match so far (all, or the step numbers given, with a diff for each failure)."
  [& steps]
  (reload)
  (golden/report! (map str steps)))
