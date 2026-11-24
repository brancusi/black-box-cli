(ns release
  "Releases: `bb dist` builds the files a release carries; `bb release X.Y.Z` makes the whole release in one step.
  It builds and publishes from this Mac, waiting on nothing; GitHub re-runs the tests in the background (check.yml).
  Black Box checks the manifest every few minutes and installs a release on every Mac whose Black Box is at least
  its `min_black_box` (docs/CONTRACT.md, CLI updates), so a release is live minutes after this finishes."
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def ^:private version-file "src/black_box_cli/version.clj")

(defn- file-version [] (second (re-find #"def version \"([^\"]+)\"" (slurp version-file))))

(defn- min-black-box [] (second (re-find #"def min-black-box\s+\"[^\"]*\"\s+\"([^\"]+)\"" (slurp version-file))))

(defn- out
  "What a command prints, trimmed; an options map (`{:dir …}`) may come first."
  [& cmd]
  (let [[opts cmd] (if (map? (first cmd)) [(first cmd) (rest cmd)] [{} cmd])]
    (str/trim (:out (apply shell (assoc opts :out :string) cmd)))))

(defn- fail [& msg] (throw (ex-info (str/join msg) {:babashka/exit 1})))

(defn dist!
  "target/blackbox-cli.jar, its .sha256 and blackbox-cli.json (the manifest). With `tag`, checks it names the version."
  [tag]
  (let [version (file-version)
        jar "target/blackbox-cli.jar"]
    (when (and tag (not= tag (str "v" version)))
      (fail "tag " tag " but version.clj says " version))
    (when-not (min-black-box) (fail "version.clj has no min-black-box"))
    (fs/create-dirs "target")
    (fs/delete-if-exists jar)
    (shell "bb uberjar" jar "-m" "black-box-cli.main")
    (let [said (out "bb" jar "--version")]
      (when (not= said version) (fail "the jar says " said ", not " version)))
    (let [sha-line (out {:dir "target"} "shasum -a 256 blackbox-cli.jar")]
      (spit "target/blackbox-cli.jar.sha256" (str sha-line "\n"))
      (spit "target/blackbox-cli.json"
            (str (json/generate-string (array-map :schema "black-box-cli.release/1"
                                                  :version version
                                                  :jar "blackbox-cli.jar"
                                                  :sha256 (first (str/split sha-line #"\s+"))
                                                  :size (fs/size jar)
                                                  :min_black_box (min-black-box))
                                       {:pretty true})
                 "\n")))
    (println jar version "(needs Black Box" (str (min-black-box) ")"))))

(defn- parse [args]
  (loop [[a & more] args m {:trailers []}]
    (cond
      (nil? a) m
      (= a "--dry-run") (recur more (assoc m :dry-run true))
      (= a "--check") (recur more (assoc m :check true))
      (= a "--trailer") (recur (rest more) (update m :trailers conj (first more)))
      (not (:version m)) (recur more (assoc m :version a))
      (not (:summary m)) (recur more (assoc m :summary a))
      :else (fail "bb release X.Y.Z [summary] [--trailer \"Key: value\"]… [--dry-run]: what's " a "?"))))

(defn- notes
  "The CHANGELOG's section for `version`, for the release's notes."
  [changelog version]
  (let [[_ body] (re-find (re-pattern (str "(?s)## " (java.util.regex.Pattern/quote version) " \\([^)]*\\)\\n(.*?)(?=\\n## |\\z)"))
                          changelog)]
    (str/trim (or body "See CHANGELOG.md."))))

(defn- preflight [version]
  (when-not (re-matches #"\d+\.\d+\.\d+" (str version)) (fail "bb release X.Y.Z: the version, e.g. 0.1.11"))
  (when (not= "main" (out "git branch --show-current")) (fail "release from main"))
  (when (seq (out "git status --porcelain")) (fail "commit or put away the changes first (git status)"))
  (shell "git fetch -q origin")
  (when (pos? (parse-long (out "git rev-list --count HEAD..origin/main"))) (fail "main is behind origin/main: pull first"))
  (when (seq (out "git tag -l" (str "v" version))) (fail "v" version " is already tagged"))
  (let [changelog (slurp "CHANGELOG.md")
        [_ unreleased] (re-find #"(?s)## Unreleased\n(.*?)(?=\n## |\z)" changelog)]
    (when (str/blank? unreleased) (fail "CHANGELOG.md has nothing under ## Unreleased"))
    (let [news (second (re-find #"(?s)\(def news.*?:version \"([^\"]+)\"" (slurp "src/black_box_cli/tutorial.clj")))]
      (when (not= news version)
        (fail "the tutorial's news starts at " news ": add what's new in " version " to tutorial/news, then `bb narrate`")))
    changelog))

(defn- published!
  "Checks the release `tag` as Black Box will download it: the jar's SHA-256 and version against its manifest."
  [tag]
  (let [version (subs tag 1)
        dir (str (fs/create-temp-dir {:prefix "bbcli-release-"}))
        repo (out "gh repo view --json nameWithOwner --jq .nameWithOwner")
        _ (shell {:dir dir} "gh release download" tag "--repo" repo)
        manifest (json/parse-string (slurp (str (fs/path dir "blackbox-cli.json"))) true)
        sha (first (str/split (out {:dir dir} "shasum -a 256 blackbox-cli.jar") #"\s+"))
        said (out "bb" (str (fs/path dir "blackbox-cli.jar")) "--version")]
    (when-not (= version (:version manifest) said) (fail "the published jar says " said ", its manifest " (:version manifest)))
    (when (not= sha (:sha256 manifest)) (fail "the published jar's SHA-256 isn't the manifest's"))
    (println "published" tag "·" sha "· needs Black Box" (:min_black_box manifest))))

(defn release!
  "bb release X.Y.Z [summary] [--trailer \"Key: value\"]… [--dry-run]; bb release X.Y.Z --check checks a
  published release's download."
  [args]
  (let [{:keys [version summary trailers dry-run check]} (parse args)
        _ (when check (published! (str "v" version)) (System/exit 0))
        changelog (preflight version)
        today (str (java.time.LocalDate/now java.time.ZoneOffset/UTC))
        tag (str "v" version)]
    (println "release" version "from" (file-version) "· needs Black Box" (min-black-box))
    (when-not dry-run
      (spit version-file (str/replace-first (slurp version-file) #"def version \"[^\"]+\"" (str "def version \"" version "\"")))
      (spit "CHANGELOG.md" (str/replace-first changelog "## Unreleased" (str "## " version " (" today ")")))
      (shell "bb editors")
      (println "If a check fails: `git checkout .` puts everything back.")
      (shell "bb test")
      (shell "bb lint")
      (shell "bb fmt")
      (shell "git add -A")
      (apply shell "git commit -q -m" (str version (when summary (str ": " summary)))
             (mapcat (fn [t] ["--trailer" t]) trailers))
      (shell "git tag" tag)
      ;; Built here, where the checks just ran; GitHub only re-runs the tests afterwards, and nothing waits on it.
      (dist! tag)
      (shell "git push -q origin main")
      (shell "git push -q origin" tag)
      (spit "target/notes.md" (notes (slurp "CHANGELOG.md") version))
      (shell "gh release create" tag "target/blackbox-cli.jar" "target/blackbox-cli.jar.sha256" "target/blackbox-cli.json"
             "--title" tag "--notes-file" "target/notes.md")
      (published! tag))))

