(ns black-box-cli.setup
  "`setup agent` (AXI §7): the Agent Skill and a SessionStart hook that shows the home view, for Claude Code
  (`~/.claude`) and Codex (`~/.agents/skills`, `~/.codex/hooks.json`). It never replaces what a person made: a skill
  without a marker line, or settings it can't read, are left as they are, and settings are copied aside before
  they change. Black Box's own skill and hook (its marker, its hook command) count as ours: this CLI takes them
  over at the cutover."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.cuts :as cuts]
   [black-box-cli.index :as index]
   [black-box-cli.out :as out]
   [black-box-cli.version :as version]
   [cheshire.core :as json]
   [clj-yaml.core :as yaml]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def marker
  "The line that makes an installed skill ours to replace."
  "<!-- Installed by `blackbox setup agent` and replaced when it runs again. Delete this line to keep your own edits. -->")

(def ^:private black-box-marker
  "The line in the skill Black Box installed before this CLI took it over."
  "<!-- Installed by Black Box and replaced when it updates. Delete this line to keep your own edits. -->")

(def ^:private default-bin "~/.local/bin/blackbox")

(def ^:private version-re
  "The line under the marker that says which version of the CLI installed the skill."
  #"<!-- blackbox skill (\S+) -->")

(defn skill-text
  "The skill as `setup agent` installs it: skills/blackbox/SKILL.md, naming `bin` (a ~ path) as the command, with
  the CLI's version under the marker line (what `setup skill-check` compares)."
  [bin]
  (-> (slurp (io/resource "blackbox/SKILL.md"))
      (str/replace default-bin bin)
      (str/replace-first marker (str marker "\n<!-- blackbox skill " version/version " -->"))))

(defn- bin-path
  "Where the command is: $BLACKBOX_BIN (~ is the home folder), else bbin's ~/.local/bin/blackbox."
  [{:keys [env home]}]
  (str/replace-first (get env "BLACKBOX_BIN" default-bin) #"^~(?=/|$)" (str/re-quote-replacement home)))

(def bundled-marker
  "The line in the launcher Black Box writes at ~/.local/bin/blackbox for the copy of this CLI it bundles."
  "# black-box: bundled-cli")

(defn- ours?
  "Whether `path` is this CLI: bbin's launcher (it names the checkout or the git repo) or Black Box's launcher for
  the bundled copy (its marker line), never a link (Black Box's old command was one, into the app)."
  [path]
  (and (not (fs/sym-link? path))
       (fs/regular-file? path)
       (let [head (byte-array 4096)
             n (with-open [in (io/input-stream (str path))] (.read in head))
             text (String. head 0 (int (max n 0)) "UTF-8")]
         (or (str/includes? text "black-box-cli") (str/includes? text bundled-marker)))))

(defn- require-bin!
  "The command's path, or an error: setup installs what calls it, so it must be this CLI."
  [{:keys [home] :as ctx}]
  (let [path (bin-path ctx)
        shown (out/home-path path home)]
    (cond
      (not (fs/exists? path))
      (out/fail! "not-installed" (str "no blackbox at " shown)
                 {:help ["Open Black Box once: an up-to-date Black Box installs `blackbox` at each start"
                         "Or install it by hand: `bbin install <this checkout or its git URL> --as blackbox`"]})

      (not (ours? path))
      (out/fail! "not-ours" (str shown " is Black Box's own command, not this CLI")
                 {:help ["Black Box installs its own skill and hook for it; this CLI takes over at the cutover"
                         (str "At the cutover, `rm " shown "` (the link only) before `bbin install … --as blackbox`:"
                              " bbin writes through a link, into Black Box's own binary")]})

      :else path)))

;; ------------------------------------------------------------------------------------------------ writing

(defn- replace!
  "Writes `text` to `path` through a temporary file beside it, so it's never half written."
  [path text]
  (fs/create-dirs (fs/parent path))
  (let [tmp (fs/create-temp-file {:dir (fs/parent path) :prefix ".blackbox-"})]
    (spit (str tmp) text)
    (fs/move tmp path {:replace-existing true :atomic-move true})))

(defn- skill!
  "Installs the skill at `path`; what it did."
  [path text]
  (let [old (when (fs/exists? path) (slurp (str path)))]
    (cond
      (= old text) "already installed"
      (and old (not (str/includes? old marker)) (not (str/includes? old black-box-marker)))
      "left as it is: edited by hand (no marker line)"
      :else (do (replace! path text) (if old "updated" "installed")))))

(defn- read-json
  "A settings file's JSON, its keys in the file's order when YAML reads it the same (most JSON), else as JSON reads
  it. nil when it isn't JSON."
  [text]
  (when-let [plain (try (json/parse-string text) (catch Exception _ nil))]
    (let [ordered (try (yaml/parse-string text :keywords false) (catch Exception _ nil))]
      (if (= plain ordered) ordered plain))))

(def ^:private printer
  (json/create-pretty-printer
   (assoc json/default-pretty-print-options :indentation 2 :indent-arrays? true :object-field-value-separator ": ")))

(defn- has-hook?
  "Whether one of an event's hook `groups` runs a command `match` says yes to."
  [groups match]
  (some (fn [group]
          (some #(match (str (get % "command"))) (when (map? group) (get group "hooks"))))
        groups))

(defn- hooks!
  "Adds each of `wanted` ({:event :command :match}) that the settings at `path` (Claude Code's and Codex's share the
  shape) don't have yet, in one write; what it did for each, in order."
  [path wanted]
  (let [existed (fs/exists? path)
        settings (if existed (read-json (slurp (str path))) {})
        hooks (when (map? settings) (get settings "hooks" {}))
        bad (cond
              (not (map? settings)) "left as it is: not JSON this can read"
              (not (map? hooks)) "left as it is: its hooks aren't an object"
              :else (some (fn [{:keys [event]}]
                            (when-not (sequential? (get hooks event []))
                              (str "left as it is: its " event " hooks aren't a list")))
                          wanted))]
    (if bad
      (mapv (constantly bad) wanted)
      (let [missing (vec (remove #(has-hook? (get hooks (:event %) []) (:match %)) wanted))
            group #(array-map "hooks" [(array-map "type" "command" "command" % "timeout" 10)])
            result (when (seq missing)
                     (when existed
                       (fs/copy path (str path ".before-blackbox-" (cuts/stamp))))
                     (replace! path (str (json/generate-string
                                          (assoc settings "hooks"
                                                 (reduce (fn [h {:keys [event command]}]
                                                           (assoc h event (conj (vec (get h event [])) (group command))))
                                                         hooks missing))
                                          {:pretty printer})
                                         "\n"))
                     (if existed "installed (the old file is kept beside it)" "installed"))]
        (mapv #(if (some #{%} missing) result "already installed") wanted)))))

(defn- codex-hooks-on?
  "Whether Codex's config turns hooks on (`hooks = true` under [features])."
  [home]
  (let [config (fs/path home ".codex" "config.toml")]
    (boolean (and (fs/exists? config) (re-find #"(?m)^\s*hooks\s*=\s*true" (slurp (str config)))))))

;; ------------------------------------------------------------------------------------------------ the command

(defn agent-cmd
  "`blackbox setup agent`: the skill and the session hook for each agent this Mac has."
  [{:keys [home] :as ctx}]
  (let [path (require-bin! ctx)
        bin (out/home-path path home)
        program (str/replace-first bin #"^~" "\\$HOME")
        command (str "\"" program "\" 2>/dev/null || true")
        text (skill-text bin)
        row (fn [agent part p result] (array-map :agent agent :part part :path (out/home-path p home) :result result))
        skip "skipped: not set up on this Mac"
        check (fn [agent] (str "\"" program "\" setup skill-check --agent " agent " 2>/dev/null || true"))
        check? #(str/includes? % "setup skill-check")
        wanted (fn [agent]
                 [{:event "SessionStart" :command command :match #(and (str/includes? % program) (not (check? %)))}
                  {:event "SessionStart" :command (check agent) :match check?}
                  {:event "UserPromptSubmit" :command (check agent) :match check?}])
        hook-rows (fn [agent p results note]
                    (let [[home-view & checks] (map #(cond-> % note (str note)) results)]
                      [(row agent "hook" p home-view)
                       (row agent "skill check" p (str/join "; " (distinct checks)))]))
        claude (fs/path home ".claude")
        codex (fs/path home ".codex")
        rows (concat
              (let [skill (fs/path claude "skills" "blackbox" "SKILL.md")
                    settings (fs/path claude "settings.json")]
                (if (fs/exists? claude)
                  (into [(row "claude" "skill" skill (skill! skill text))]
                        (hook-rows "claude" settings (hooks! settings (wanted "claude")) nil))
                  [(row "claude" "skill" skill skip) (row "claude" "hook" settings skip)
                   (row "claude" "skill check" settings skip)]))
              (let [skill (fs/path home ".agents" "skills" "blackbox" "SKILL.md")
                    hooks (fs/path codex "hooks.json")]
                (if (fs/exists? codex)
                  (into [(row "codex" "skill" skill (skill! skill text))]
                        (hook-rows "codex" hooks (hooks! hooks (wanted "codex"))
                                   (when-not (codex-hooks-on? home) "; Codex runs it once [features] hooks = true")))
                  [(row "codex" "skill" skill skip) (row "codex" "hook" hooks skip)
                   (row "codex" "skill check" hooks skip)])))
        kept (filter #(str/starts-with? (:result %) "left as it is: edited") rows)]
    [(array-map :bin bin :parts (vec rows))
     (concat
      (map #(str "To take the new skill, delete " (:path %) " (or its edits) and run `blackbox setup agent` again") kept)
      ["A new Claude Code or Codex session starts with `blackbox`'s home view"
       "When the skill is updated, a running session is told to re-read it at its next prompt"])]))

(defn- installed-version
  "The version of the CLI that installed the skill at `path`: its version line, \"earlier\" for a skill from before
  there was one, nil when there's no skill."
  [path]
  (when (fs/regular-file? path)
    (or (second (re-find version-re (slurp (str path)))) "earlier")))

(defn skill-check-cmd
  "`blackbox setup skill-check`, the hook `setup agent` installs on SessionStart and UserPromptSubmit: it reads the
  hook's JSON on stdin and notes which skill the session started with. At a later prompt, if a newer skill has been
  installed since (Black Box runs `setup agent` when it updates), it prints one line telling the agent to re-read
  it; otherwise nothing. State is a small file per session in the cache, pruned after 30 days."
  [{:keys [home opts] :as ctx}]
  (let [agent (:agent opts)
        skill (if (= "codex" agent)
                (fs/path home ".agents" "skills" "blackbox" "SKILL.md")
                (fs/path home ".claude" "skills" "blackbox" "SKILL.md"))
        input (try (json/parse-string (slurp *in*)) (catch Exception _ nil))
        id (some-> (get input "session_id") str (str/replace #"[^A-Za-z0-9_-]" "") not-empty)
        installed (installed-version skill)
        dir (:sessions (index/paths ctx))]
    (when (and id installed (#{"claude" "codex"} agent))
      (let [f (fs/path dir (str agent "-" id))
            seen (when (fs/exists? f) (str/trim (slurp (str f))))
            start? (= "SessionStart" (get input "hook_event_name"))]
        (when start?
          (when (fs/directory? dir)
            (doseq [old (fs/list-dir dir)
                    :when (< (.toMillis (fs/last-modified-time old)) (- (System/currentTimeMillis) (* 30 24 3600 1000)))]
              (fs/delete-if-exists old))))
        (when (or start? (not= seen installed))
          (fs/create-dirs dir)
          (spit (str f) installed))
        (when (and seen (not start?) (not= seen installed))
          (out/text! (str "The blackbox skill on this Mac was updated (to " installed ") after this session read it. "
                          "Before using blackbox again, re-read " (out/home-path skill home)
                          ": it may have new commands (`blackbox tutorial` lists what it can do).\n")))))
    nil))
