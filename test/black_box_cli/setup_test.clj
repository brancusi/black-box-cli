(ns black-box-cli.setup-test
  "`setup agent` in a temporary home: the skill and the hook for Claude Code and Codex, installed once, never over a
  hand-edited file or settings it can't read, Black Box's own taken over; and the skill itself checked against the
  commands it names."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.flags :as flags]
   [black-box-cli.setup :as setup]
   [black-box-cli.support :as s]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private hook-command "\"$HOME/.local/bin/blackbox\" 2>/dev/null || true")

(defn- home-with
  "A temporary home with this CLI at ~/.local/bin/blackbox (as bbin installs it) and the agents' folders named."
  [& agents]
  (let [home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
        bin (fs/path home ".local" "bin" "blackbox")]
    (fs/create-dirs (fs/parent bin))
    (spit (str bin) "#!/usr/bin/env bb\n; {:coords {:bbin/url \"file:///somewhere/black-box-cli\"}}\n")
    (doseq [a agents] (fs/create-dirs (fs/path home a)))
    home))

(defn- setup! [home]
  (let [{:keys [exit out]} (s/cli* {:home home} ["--format" "edn" "setup" "agent"])]
    (assoc (read-string out) :exit exit)))

(defn- results [res] (mapv (juxt :agent :part :result) (:parts res)))

(defn- file [home & parts] (str (apply fs/path home parts)))

(deftest a-fresh-mac-gets-the-skill-and-the-hook
  (let [home (home-with ".claude" ".codex")]
    (is (= [["claude" "skill" "installed"] ["claude" "hook" "installed"]
            ["codex" "skill" "installed"] ["codex" "hook" "installed; Codex runs it once [features] hooks = true"]]
           (results (setup! home))))
    (is (= "~/.local/bin/blackbox" (:bin (setup! home))))
    (let [skill (slurp (file home ".claude" "skills" "blackbox" "SKILL.md"))]
      (is (str/includes? skill setup/marker))
      (is (= skill (slurp (file home ".agents" "skills" "blackbox" "SKILL.md")))))
    (is (= {"hooks" {"SessionStart" [{"hooks" [{"type" "command" "command" hook-command "timeout" 10}]}]}}
           (json/parse-string (slurp (file home ".claude" "settings.json")))
           (json/parse-string (slurp (file home ".codex" "hooks.json")))))
    (testing "a second run changes nothing"
      (is (= [["claude" "skill" "already installed"] ["claude" "hook" "already installed"]
              ["codex" "skill" "already installed"] ["codex" "hook" "already installed; Codex runs it once [features] hooks = true"]]
             (results (setup! home))))
      (is (empty? (fs/glob (fs/path home ".claude") "*.before-blackbox-*"))))))

(deftest settings-are-kept-and-copied-aside
  (let [home (home-with ".claude" ".codex")
        before (str "{\n  \"model\": \"x\",\n  \"hooks\": {\"SessionStart\": [{\"matcher\": \"\", \"hooks\": "
                    "[{\"type\": \"command\", \"command\": \"other\"}]}]},\n  \"env\": {\"A\": \"1\"}\n}\n")
        settings (file home ".claude" "settings.json")]
    (spit settings before)
    (spit (file home ".codex" "config.toml") "[features]\nhooks = true\n")
    (is (= ["installed (the old file is kept beside it)" "installed"]
           (mapv :result (filter #(= "hook" (:part %)) (:parts (setup! home))))))
    (let [after (slurp settings)
          [copy & more] (fs/glob (fs/path home ".claude") "settings.json.before-blackbox-*")]
      (is (= ["model" "hooks" "env"] (map second (re-seq #"(?m)^  \"(\w+)\"" after))) "the file's own order")
      (is (= ["other" hook-command] (map #(-> % (get "hooks") first (get "command"))
                                         (get-in (json/parse-string after) ["hooks" "SessionStart"]))))
      (is (= before (slurp (str copy))))
      (is (nil? more)))))

(deftest never-over-what-a-person-made
  (let [home (home-with ".claude")
        skill (file home ".claude" "skills" "blackbox" "SKILL.md")
        settings (file home ".claude" "settings.json")]
    (fs/create-dirs (fs/parent skill))
    (spit skill "my own notes\n")
    (spit settings "{ not json")
    (let [res (setup! home)]
      (is (= [["claude" "skill" "left as it is: edited by hand (no marker line)"]
              ["claude" "hook" "left as it is: not JSON this can read"]
              ["codex" "skill" "skipped: not set up on this Mac"] ["codex" "hook" "skipped: not set up on this Mac"]]
             (results res)))
      (is (str/starts-with? (first (:help res)) "To take the new skill, delete ~/.claude/skills/blackbox/SKILL.md")))
    (is (= "my own notes\n" (slurp skill)))
    (is (= "{ not json" (slurp settings)))
    (testing "hooks that aren't the shape Claude Code reads"
      (spit settings "{\"hooks\": []}")
      (is (= "left as it is: its hooks aren't an object" (:result (second (:parts (setup! home)))))))))

(deftest black-box-s-own-are-taken-over
  (let [home (home-with ".claude")
        skill (file home ".claude" "skills" "blackbox" "SKILL.md")]
    (fs/create-dirs (fs/parent skill))
    (spit skill "---\nname: blackbox\n---\n<!-- Installed by Black Box and replaced when it updates. Delete this line to keep your own edits. -->\nold\n")
    (spit (file home ".claude" "settings.json")
          (json/generate-string {"hooks" {"SessionStart" [{"hooks" [{"type" "command" "command" hook-command}]}]}}))
    (is (= [["claude" "skill" "updated"] ["claude" "hook" "already installed"]] (take 2 (results (setup! home)))))
    (is (str/includes? (slurp skill) setup/marker))))

(deftest only-for-this-cli
  (let [home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
        bin (fs/path home ".local" "bin" "blackbox")
        code #(-> (s/cli* {:home home} ["--format" "edn" "setup" "agent"]) :out read-string :error :code)]
    (fs/create-dirs (fs/path home ".claude"))
    (is (= "not-installed" (code)))
    (testing "Black Box's own command, linked into ~/.local/bin, isn't this CLI"
      (fs/create-dirs (fs/parent bin))
      (let [app (fs/create-temp-file {:prefix "BlackBox"})]
        (fs/create-sym-link bin app)
        (is (= "not-ours" (code)))))
    (is (not (fs/exists? (fs/path home ".claude" "skills"))))))

(deftest black-box-s-launcher-is-this-cli
  (testing "Black Box writes its own script at ~/.local/bin/blackbox for the copy it bundles; its marker makes it ours"
    (let [home (str (fs/create-temp-dir {:prefix "bbcli-home-"}))
          bin (fs/path home ".local" "bin" "blackbox")]
      (fs/create-dirs (fs/parent bin))
      (fs/create-dirs (fs/path home ".claude"))
      (spit (str bin) (str "#!/bin/sh\n" setup/bundled-marker "\nexport BLACKBOX_TOOLS=/x\nexec /x/bb /x/blackbox-cli.jar \"$@\"\n"))
      (is (= "~/.local/bin/blackbox" (:bin (setup! home))))
      (is (fs/exists? (fs/path home ".claude" "skills" "blackbox" "SKILL.md"))))))

(deftest the-skill-names-real-commands
  (let [text (setup/skill-text "~/.local/bin/blackbox")
        lines (->> (re-seq #"`(blackbox [^`]*)`" text) (map second)
                   (remove #(re-find #"[<…]" %)))]
    (is (str/includes? text setup/marker))
    (is (< 15 (count lines)))
    (testing "each `blackbox …` in the skill reads as a command line"
      (doseq [line lines
              :let [words (rest (str/split line #"\s+"))]]
        (testing line
          (when (seq words)
            (let [[spec tokens] (flags/find-command words)]
              (is spec)
              (when (seq tokens) ; a bare `blackbox frame` is the command's name in a sentence
                (is (map? (flags/parse spec tokens)))))))))
    (testing "and every command but setup is in it"
      (doseq [{:keys [path]} flags/commands
              :when (not= ["setup" "agent"] path)]
        (is (str/includes? text (str "blackbox " (str/join " " path))) (str/join " " path))))))
